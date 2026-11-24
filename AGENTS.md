# AGENTS.md

Guidance for AI coding agents and contributors working in this repository.

## Documentation rule (mandatory)

Every change MUST be documented in the same change set, in **en-US**:

- `README.md`: user-facing behavior, configuration and operation, plus a new entry in its
  **Development log** section.
- `AGENTS.md` (this file): conventions, architecture notes, gotchas and verification steps that the
  next agent needs.
- `CLAUDE.md`: only Claude Code–specific notes. It imports this file.

A change without these updates is not done.

## What this project is

A Spring Boot service that provides near-real-time search indexing for Alfresco Community 26.2+
(OpenSearch/Elasticsearch). It complements the official polling batch indexer:

- consumes ActiveMQ `alfresco.repo.event2` events;
- polls `alf_acl_change_set`, because Community publishes no permission events;
- re-runs the upstream Alfresco Search Community pipeline for the changed nodes.

Source of truth for requirements and design: `specs/001-live-indexing/` (Spec Kit). Principles:
`.specify/memory/constitution.md`.

## Architecture (src/main/java/org/acs/liveindexing)

| Class | Role |
|-------|------|
| `LiveIndexingApp` | Boot app. Imports the same upstream configs as `BatchIndexingApp` and scans `org.alfresco.elasticsearch.batchindexing`, excluding `OneShotJobRunner` and `BatchIndexingApp`. |
| `NodeEventListener` | `@JmsListener` on `${live.topic}`. Pushes node UUIDs into `PendingNodes`. |
| `NodeEventParser` | Extracts `data.resource.id` when `@type` is `NodeResource`. |
| `PendingNodes` | Lock-free queue. `drain()` returns the distinct UUIDs. |
| `LiveIndexer` | Scheduled flush. Drains UUIDs, adds ACL-changed nodes, resolves `alf_node` ids by store, hard-deletes trashcan docs and runs the `liveIndex` job. |
| `LiveIndexJobConfig` | `liveIndex` job: upstream `AlfrescoExplicitIdSetItemReader` → `processors` → `writer`. This mirrors upstream `OneShotReindexDlqConfig`. |

## Hard rules

- **Never re-implement index document building.** Documents MUST come from upstream beans
  (`processors`, `writer`), so they match the official indexer.
- **Never write the batch indexer's watermark.** `alfresco.reindex.jobName=liveIndex` disables the
  upstream watermark store and scheduler. Do not remove it.
- **Read-only DB access.** Only SELECTs against the repository DB.
- **Minimal code (ponytail).** Mark deliberate shortcuts with a `// ponytail:` comment naming the ceiling.
- **One runnable check per non-trivial logic.** Use JUnit in `src/test`.

## Gotchas (learned the hard way)

- **Upstream artifacts:** `artifacts.hyland.dev` returns 402 anonymously, so use
  `https://maven.alfresco.com/nexus/repository/public`. Version `5.10.0-A.5` is pinned. The 5.7.1
  reindexing libraries are not published.
- **App config location:** our config lives in `src/main/resources/config/application.properties`.
  A root `application.properties` would shadow the upstream jar's defaults
  (e.g. `alfresco.mediation.filter-file`) and break startup.
- **Stale jar:** after moving resources, run `mvn clean`, or the old jar keeps stale files.
- **Permission events:** Community `EventGenerator` binds no permission policy, so
  `permission.Updated` never arrives. ACL changes come from the `alf_acl_change_set` poll.
- **Trashcan deletes:**
  - The 5.10 pipeline soft-deletes archived nodes (`ALIVE=false` with full fields).
  - The ACS 26.2.0 query path does not filter that flag, so we hard-delete instead
    (`live.hard-delete-archived=true`).
  - A trashcan delete leaves an `archive` row plus a `workspace` `sys:deleted` tombstone with the
    same uuid.
- **Local Docker validation:**
  - The ACS image needs the metadata keystore `-D` options (already in the compose file).
  - On this dev machine, host port 8080 is taken by another service; call ACS via
    `docker exec docker-alfresco-1 curl ...`.

## Target production environment (assessed 2026-11-24, read-only)

The production ACS runs in a Docker Compose project in `/var/alfresco` on the Alfresco VM. Access it
over SSH with the host alias from the operator's `~/.ssh/config`. Facts that matter for this service:

- **Stack versions:**
  - ACS Community 26.2.0, Share 26.2.1, Postgres 17.9.
  - ActiveMQ 6.2.6; the repo is wired to the broker.
  - OpenSearch **2.19.7**, not the 2.11.1 we validated on.
- **Writers already in the `alfresco` index (454k docs):**
  - `batch-indexer-os`: official batch indexer **5.7.0**, 15 s polling.
  - `batch-indexer-aclsync`: a second 5.7.0 instance. Metadata+path only, with its own watermark
    `alfresco-reindex-state-aclsync`; it is used for ACL backfill.
  - `alfresco-aclsync`: a custom ACS module (`/var/alfresco/alfresco-aclsync`). It polls
    `alf_acl_change_set` and partially updates `READER`/`DENIED`. At assessment time it was
    `enabled=true`, `dry-run=true`.
- **OpenSearch is memory-bound:** a 2 GiB container limit with a 1 GiB heap, measured at
  1.77 GiB / 67% heap. An operator monitor alerts at 1894 MiB.
- **Custom content model:** `pge-norma-model.xml`. The upstream indexer needs a complete prefix map
  for custom namespaces.
- **Other agents/operators work on this host concurrently.** Check `ps`/`docker ps` before changing
  anything there.

Shadow deployment (running since 2026-11-24):

- **Location and network:**
  - Lives in `/var/alfresco/live-indexing/`: `compose.prod-shadow.yml`, `Dockerfile`, `target/*.jar`,
    `compare_shadow.py`, and a `.env` (mode 600).
  - The `.env` is generated on the host from the running `batch-indexer-os`/`activemq` container
    env. Never copy it out.
  - The container `live-indexing-shadow` sits on external network `alfresco` at fixed IP
    `172.20.1.60`. The network uses fixed IPs, so pick a free one.
- **Writes only to these indices:**
  - `alfresco-live-shadow`, created with `alfresco`'s mapping/analysis cloned and
    `number_of_replicas=0`;
  - `alfresco-reindex-dead-letter-live-shadow`.
- **Settings:** content indexing is off, and `live.hard-delete-archived=false`.
- **Replicas:** OpenSearch is a single node. Any index created with explicit settings MUST use
  `number_of_replicas: 0`, or the cluster turns yellow. The operator added the index template
  `alfresco-single-node-replicas` for `alfresco-reindex-*` and `alfresco-live-*`.
- **Compare:**
  `IGNORE_CONTENT=1 python3 /var/alfresco/live-indexing/compare_shadow.py http://172.20.1.29:9200`.
  - It ignores timing fields and compares lists as sets, because READER order differs between 5.7
    and 5.10.
  - "Missing in main" right after an upload is batch lag; re-run after about 1 min.
  - Known benign diff: batch 5.7.0 writes unresolved `null:` path segments (e.g.
    `null:GROUP_SITE_CREATORS`), while 5.10 omits the prefix.
- **Do not leave helper images on the host.** Remove anything pulled only for diagnostics
  (e.g. `curlimages/curl`).
- **Rollback:**
  1. `docker compose -f compose.prod-shadow.yml down`
  2. `DELETE alfresco-live-shadow` and `DELETE alfresco-reindex-dead-letter-live-shadow`
- **Coordination:** the operator session "Migração Alfresco" owns OpenSearch and indexing on this host.
  Tell it before changing anything that writes to `alfresco`.

Rules for any production test:

1. Shadow first: `elasticsearch.indexName=<separate index>`, `alfresco.reindex.contentIndexingEnabled=false`,
   and `live.hard-delete-archived=false`.
2. Compare shadow docs with `alfresco` field by field before writing to `alfresco`.
3. Never run in production without the operator's explicit go-ahead and an agreed rollback (stop the
   container).

## Build and verify

```bash
mvn clean verify                                   # unit tests
mvn -DskipTests package
docker compose -f docker/compose.live-indexing.yml up -d --build   # E2E stack
docker compose -f docker/compose.live-indexing.yml --profile batch up -d batch-indexer
docker compose -f docker/compose.live-indexing.yml --profile batch down   # tear down
```

E2E scenarios: `specs/001-live-indexing/quickstart.md`. Use JDK 17. The Maven Wrapper (`./mvnw`) is
provided.

## Spec Kit workflow

New feature or change:

1. `/speckit-specify`
2. `/speckit-plan`
3. `/speckit-tasks`
4. `/speckit-implement`

Keep `specs/<feature>/` in sync with what was actually built. Record findings in `research.md`.
