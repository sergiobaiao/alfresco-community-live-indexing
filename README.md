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
