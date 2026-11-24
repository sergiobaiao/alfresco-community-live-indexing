# Alfresco Community Live Indexing

Near-real-time (≈1–2 s) search indexing for **Alfresco Community 26.2+** with Alfresco Search Community
(OpenSearch / Elasticsearch).

Out of the box, Community indexes by **polling** the repository DB every 30 s
(`alfresco-elasticsearch-batch-indexing`). This service adds the **event-driven** path that only
Enterprise has, without re-implementing the index format:

```
ACS ──event2──▶ ActiveMQ topic alfresco.repo.event2
                    │
                    ▼
            live-indexing (this app)
            1. collect node UUIDs (dedup, 1 s window)
            2. alf_node uuid → dbId (read-only JDBC)
            3. upstream pipeline: AlfrescoExplicitIdSetItemReader → processors → writer
                    │  (metadata + READER/DENIED + path + content via /api/solr/textContent)
                    ▼
               OpenSearch index "alfresco"  ◀── official batch indexer (safety net, unchanged)
```

Key points:

- **Same documents as the official indexer.** The pipeline comes from
  [Alfresco/alfresco-community-search](https://github.com/Alfresco/alfresco-community-search)
  (LGPL-3.0), artifact `alfresco-elasticsearch-batch-indexing:5.10.0-A.5`.
- **Events are triggers only.** Community events carry no reader/denied authorities, so node state
  (including ACLs) is always read from the DB.
- **ACL changes are polled, not evented.** Community publishes no permission events, so each flush also
  picks up nodes from new `alf_acl_change_set` rows.
- **Trashcan deletes remove the document** (`live.hard-delete-archived=true`). ACS 26.2.0 does not
  filter the upstream 5.10 soft-delete (`ALIVE=false`).
- **Never touches the batch cursor.** `alfresco.reindex.jobName=liveIndex` disables the upstream
  watermark store and scheduler in this process.
- **Keep the batch indexer running.** It does the initial/historical indexing and repairs whatever this
  service misses: lost events, cascades (folder rename, ACL inheritance on descendants), and failures.

## Build

```bash
./mvnw verify
```

Requires JDK 17. Artifacts resolve anonymously from `https://maven.alfresco.com/nexus/repository/public`.

## Run

Full validation stack: ACS 26.2, OpenSearch 2.11.1, Transform, ActiveMQ, Postgres, and this app.
The batch indexer is behind the `batch` profile.

```bash
./mvnw -DskipTests package
docker compose -f docker/compose.live-indexing.yml up -d --build
docker compose -f docker/compose.live-indexing.yml --profile batch up -d   # add the safety net
```

Standalone:

```bash
java -jar target/alfresco-community-live-indexing-*.jar \
  --spring.activemq.broker-url=tcp://activemq:61616 \
  --spring.datasource.url=jdbc:postgresql://db:5432/alfresco \
  --spring.datasource.username=<read-only-user> --spring.datasource.password=<pwd> \
  --spring.elasticsearch.uris=http://opensearch:9200 \
  --alfresco.acs.url=http://alfresco:8080 \
  --alfresco.acceptedContentMediaTypesCache.baseurl=http://transform-core-aio:8090/transform/config \
  --alfresco.content.transform.sharedSecret=<solr.sharedSecret>
```

All properties are listed in
[specs/001-live-indexing/contracts/configuration.md](specs/001-live-indexing/contracts/configuration.md).
Upstream batch-indexing tuning properties (`alfresco.reindex.*`, `alfresco.content.transform.*`)
apply unchanged.

## Operate

- `GET :8080/actuator/health`
- `GET :8080/actuator/prometheus`, which includes:
  - `live_events_received_total`
  - `live_events_ignored_total`
  - `live_nodes_flushed_total`
  - `live_flush_failures_total`

## Validated (local Docker stack, ACS 26.2.0 + OpenSearch 2.11.1)

| Change | Visible in index |
|--------|------------------|
| Create document (metadata + full text) | 0.45 s |
| Update property | 0.47 s |
| Permission change (READER) | 1.03 s |
| Trashcan delete (gone from search) | 0.43 s |

The official batch indexer 5.7.1 ran alongside with no conflicts. This service never created the
watermark document.

## Limits (v1)

- ACL cascades larger than 10,000 nodes per flush are left to the batch indexer.

- Single instance only. The topic subscription is non-durable: events published while the service is
  down are left to the batch indexer.
- Descendant cascades are left to the batch indexer.
- Each flush creates one Spring Batch job execution in the upstream in-memory job repository.
  Restart the service periodically, which upstream also recommends for the batch indexer.
- Upstream versions are alpha. Pin them, and re-verify the index contract on every bump.

## Spec Kit

Design artifacts are in [specs/001-live-indexing/](specs/001-live-indexing/): spec, plan, research,
data model, contracts, quickstart, and tasks. The constitution is in
[.specify/memory/constitution.md](.specify/memory/constitution.md).

## Contributing / agents

Contributor and AI-agent guidance lives in [AGENTS.md](AGENTS.md); [CLAUDE.md](CLAUDE.md) imports it.
Every change must update this README (including the log below) and AGENTS.md, in en-US.

## Development log

### 2026-11-24: Initial version (v0.1.0)

- **Feasibility research:**
  - Alfresco Community 26.2 replaced Solr with Alfresco Search Community, a polling batch indexer
    with a 30 s cycle.
  - Event-driven live indexing exists only in Enterprise.
  - The upstream source is open (LGPL-3.0) in
    [Alfresco/alfresco-community-search](https://github.com/Alfresco/alfresco-community-search).
- **Spec Kit:** the full Spec Kit flow ran (constitution → spec → plan → tasks → implement); the
  artifacts are in `specs/001-live-indexing/`.
- **Implementation:**
  - JMS listener on `alfresco.repo.event2`, with a 1 s dedup window.
  - The upstream pipeline is reused via an explicit-id `liveIndex` job.
  - `alfresco.reindex.jobName=liveIndex` keeps the service away from the batch watermark.
- **E2E findings, fixed:**
  - Community emits no permission events, so ACL changes are polled from `alf_acl_change_set`.
  - The ACS 26.2.0 query path still returns 5.10 soft-deleted docs, so trashcan deletes are hard
    deletes (`live.hard-delete-archived`).
  - The config moved to `config/application.properties` so the upstream defaults still load.
- **Validation:** done on a local Docker stack (ACS 26.2.0, OpenSearch 2.11.1, batch indexer 5.7.1
  alongside):

  | Change | Visible in index |
  |--------|------------------|
  | Create | 0.45 s |
  | Update | 0.47 s |
  | ACL change | 1.03 s |
  | Delete | 0.43 s |

- **Docs:** added AGENTS.md and CLAUDE.md, plus this log.

### 2026-11-24: Production readiness assessment (read-only, nothing changed)

The production VM was inspected over SSH; no files, containers or data were modified. Verdict:
**not safe to enable against the live `alfresco` index yet.** The main risks, in order:

1. **OpenSearch memory headroom:** the 2 GiB container is at about 1.77 GiB, and an operator monitor
   is already watching for OOM. Another bulk writer can tip it over.
2. **Index format version skew:**
   - Production indexes with batch indexer 5.7.0; this service uses the 5.10.0-A.5 libraries.
   - Documents for the same node could differ (soft-delete/archive fields).
   - Production runs OpenSearch 2.19.7, while validation ran on 2.11.1.
3. **Overlap with existing ACL work:**
   - The custom `alfresco-aclsync` module and a second batch indexer already handle ACL changes.
   - This service's ACL poll would add a third writer for `READER`/`DENIED`.
4. **Concurrent changes:** other operators/agents were actively changing the stack during the
   assessment.
5. **Other:**
   - Custom model namespaces need a complete prefix map, or nodes are silently skipped.
   - The DB user is not read-only.

Recommended path: run it as a shadow first (see AGENTS.md, "Target production environment").

### 2026-11-24: Production shadow deployment

- **OpenSearch limits raised:** container memory 2 GiB → 4 GiB and heap 1 GiB → 2 GiB, with user
  approval.
  - Only the `opensearch` container was recreated. The cluster was back to green in about 35 s
    with all 454,606 docs.
  - Compose backup: `docker-compose.yml.bak-pre-os4g-20261124-105209`.
- **Shadow deployed:** `docker/compose.prod-shadow.yml`.
  - It writes only to `alfresco-live-shadow`, which was created with the production mapping and
    analyzers.
  - Content indexing is off and soft deletes are kept. The live `alfresco` index and the official
    batch indexer are untouched.
- **New tool:** `tools/compare_shadow.py` compares shadow documents with the production index field by
  field.
- **Status:** the broker publishes `alfresco.repo.event2` and the shadow is subscribed, but there was no
  repository activity in the first minutes, so no documents have been compared yet.

### 2026-11-24: Shadow comparison on real production traffic

About 15 minutes of real user activity: 165 events, 113 node reindexes, 0 failures, empty dead letter.
Shadow memory use is about 300 MiB.

- **Result:** 367 of 368 shadow documents are identical to the production index
  (`IGNORE_CONTENT=1 tools/compare_shadow.py`; timing fields ignored, lists compared as sets).
- **The one difference** is the `PATH` of the `admin` person node. Batch 5.7.0 writes an unresolved
  `null:GROUP_SITE_CREATORS` segment, while the 5.10 libraries omit the prefix. It does not affect
  document search.
- **Latency:** newly uploaded files appeared in the shadow before the batch indexer had picked them
  up (batch lag ≈ 15 s or more). The comparison script reports them as "missing in main" until the
  batch catches up.
- **Pre-existing production issue found:** `batch-indexer-os` logs
  `impossible to get prefixed name of title / iptcxmpAspect / creator`. The IPTC/XMP namespaces are
  missing from its default prefix map, so those aspect properties are not indexed. This is the known
  prefix-map gap in the upstream guide.
- **Script fixes:** `compare_shadow.py` now ignores `reindexingStartTime`, compares lists without
  order (READER order differs between versions), and supports `IGNORE_CONTENT=1`.
