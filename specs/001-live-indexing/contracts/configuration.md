# Contract: Configuration and Operational Endpoints

## Configuration (Spring properties / env vars)

Own properties:

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `live.topic` | `LIVE_TOPIC` | `alfresco.repo.event2` | Event topic |
| `live.flush-interval` | `LIVE_FLUSHINTERVAL` | `1s` | Coalescing window / flush delay |
| `live.hard-delete-archived` | `LIVE_HARDDELETEARCHIVED` | `true` | Trashcan delete removes the document (ACS 26.2.0); `false` = upstream soft delete |

Required upstream properties (same names as the batch indexer):

| Property | Example |
|----------|---------|
| `spring.activemq.broker-url` | `tcp://activemq:61616` |
| `spring.datasource.url` / `username` / `password` | read-only repository DB user |
| `spring.elasticsearch.uris` | `http://opensearch:9200` |
| `elasticsearch.indexName` | `alfresco` |
| `alfresco.acs.url` | `http://alfresco:8080` |
| `alfresco.content.transform.sharedSecret` | matches repo `solr.sharedSecret` |
| `alfresco.acceptedContentMediaTypesCache.baseurl` | `http://transform-core-aio:8090/transform/config` |
| `alfresco.reindex.contentIndexingEnabled` | `true` (set `false` to skip full text, FR-011) |

Fixed by the application (do not override): `alfresco.reindex.jobName=liveIndex`.

## Operational endpoints (Spring Boot Actuator, port 8080)

- `GET /actuator/health` — includes `db`, `jms` and search-engine contributors.
- `GET /actuator/prometheus` — counters:
  - `live_events_received_total`
  - `live_events_ignored_total`
  - `live_nodes_flushed_total`
  - `live_nodes_deleted_total`
  - `live_flush_failures_total`
  plus upstream reindex metrics.
