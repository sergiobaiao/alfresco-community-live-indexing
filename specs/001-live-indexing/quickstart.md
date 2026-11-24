# Quickstart: Validate Live Indexing

## Prerequisites

- Running Alfresco Community 26.2 stack with `index.subsystem.name=elasticsearch`, OpenSearch
  2.11.1, Transform Core, PostgreSQL, ActiveMQ and the official batch indexer
  (see [aborroy/alfresco-search-community-deployments](https://github.com/aborroy/alfresco-search-community-deployments)).
- Repository event publishing enabled (default when ActiveMQ is configured).
- JDK 17.

## Build and unit checks

```bash
./mvnw verify
```

Expected: build succeeds, unit tests green.

## Run

```bash
docker compose -f docker/compose.live-indexing.yml up -d --build
```

or `java -jar target/alfresco-community-live-indexing-*.jar` with the properties from
[contracts/configuration.md](contracts/configuration.md).

## Scenarios

1. **Latency (US1/SC-001)**: stop the batch indexer, upload `hello.txt` containing "zebra42",
   search `zebra42` through `POST /alfresco/api/-default-/public/search/versions/1/search` with a
   facet (forces index execution). Expected: hit within 5 s.
2. **Permissions (US2)**: break inheritance on the file and remove `GROUP_EVERYONE`; search as a
   non-admin user. Expected: no hit within 5 s.
3. **Delete (US3)**: delete the file. Expected: gone from results within 5 s.
4. **Ops (US4)**: `curl :8080/actuator/health` → `UP`; `curl :8080/actuator/prometheus | grep live_`
   shows counters incrementing.
5. **Safety net (SC-004)**: stop this service, change a file, start the batch indexer. Expected: change
   indexed within one batch cycle; `alfresco-reindex-state` watermark untouched by this service.
