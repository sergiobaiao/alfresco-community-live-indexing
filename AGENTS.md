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
