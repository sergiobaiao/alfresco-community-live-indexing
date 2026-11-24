# Research: Near-Real-Time Live Indexing

## R1. Which upstream code produces index documents?

- **Decision**: Depend on `org.alfresco:alfresco-elasticsearch-batch-indexing:5.10.0-A.5` (plain jar,
  not `-app`) plus its transitive `alfresco-search-reindexing-core`, `alfresco-search-opensearch-client`,
  `alfresco-search-index-model`, `alfresco-database-connector`, `acs-event-model`.
- **Rationale**: The batch indexer's `processors` bean (`ItemProcessor<AlfrescoNode, ReindexingEvent>`)
  computes metadata, READER/DENIED and path from the DB; its `writer` bean composes the metadata,
  path and content (`/api/solr/textContent`) writers. Reusing both guarantees index contract fidelity
  (Constitution I/II). `AlfrescoExplicitIdSetItemReader` reads an arbitrary set of node dbIds — exactly
  what a live trigger needs. The upstream DLQ job (`OneShotReindexDlqConfig.reindexDlqStep`) wires
  reader + processors + writer the same way; we mirror it.
- **Alternatives**: Building documents from the raw event (rejected: Community events carry no
  reader/denied authorities — only `acs-event-model` and `alfresco-community-search` set them);
  re-implementing the encoder (rejected: Constitution I).

## R2. Where are the artifacts?

- **Decision**: Maven repo `https://maven.alfresco.com/nexus/repository/public`.
- **Rationale**: The README's `artifacts.hyland.dev` returns `402 Access to the repository has been
  restricted` anonymously. Nexus serves `5.10.0-A.5` (latest non-`trashcan` tag) anonymously,
  including the plain batch-indexing jar.
- **Risk**: Alpha versions; internal API may move. Pin the version; upgrades re-verify (Workflow).

## R3. Preventing interference with the official batch indexer

- **Decision**: Set `alfresco.reindex.jobName=liveIndex`.
- **Rationale**: In `ContinuousReindexingConfig`, both `ElasticsearchWatermarkStore` (whose
  `WatermarkBootstrap` writes the cursor) and `ContinuousReindexingService` are
  `@ConditionalOnExpression("'${alfresco.reindex.jobName:}'.isEmpty()")`. A non-empty job name removes
  them, so this service never touches the watermark (Constitution III). The side effect is that
  `OneShotJobRunner` becomes active (it runs the named job and calls `System.exit`), so it is excluded
  from component scanning.

## R4. Event source and parsing

- **Decision**: JMS listener on ActiveMQ topic `alfresco.repo.event2` (`spring-boot-starter-activemq`,
  pub-sub). Parse JSON with Jackson tree; take `data.resource.id` when `data.resource["@type"]` is
  `NodeResource`. Event types `org.alfresco.event.node.{Created,Updated,Deleted}` and
  `org.alfresco.event.permission.Updated` all carry a `NodeResource`.
- **Rationale**: Event is only a trigger (Constitution II); only the node UUID is needed. No
  dependency on the event model classes for parsing keeps it tolerant to schema additions.
- **Alternatives**: `alfresco-java-sdk` event starter (rejected: extra dependency, Spring Boot version
  coupling); durable subscription (deferred: lost events are repaired by the batch indexer).

## R5. Coalescing and UUID → dbId

- **Decision**: Collect UUIDs in a concurrent set; a single scheduled flusher (fixed delay,
  default 1 s) drains it, resolves dbIds with `SELECT id FROM alf_node WHERE uuid IN (...)` on the
  read-only repository datasource, and launches a Spring Batch job `liveIndex` synchronously.
- **Rationale**: Set gives deduplication (FR-004, SC-005); single flusher means the step-scoped reader
  can take ids from a shared holder without races. Archived nodes keep their row/uuid, so deletes
  flow through the same pipeline (upstream `DeletionTypeChecker`).
- **Ceiling**: One job execution per flush in the upstream in-memory HSQLDB job repository → memory
  grows with uptime. Upstream documents periodic restarts for the same reason; noted with a
  `ponytail:` comment. Upgrade path: resourceless job repository or direct reader/processor/writer calls.

## R7. Permission changes (found during E2E validation)

- **Finding**: Community `EventGenerator` binds no permission policy, so `org.alfresco.event.permission.Updated`
  is never published (verified: ACL PUT produced no event). The enum value only exists in the shared
  event model.
- **Decision**: Each flush also selects nodes whose ACL belongs to an `alf_acl_change_set` with
  `commit_time_ms` newer than the last one seen (same idea as the Solr ACL tracker). Capped at 10,000
  nodes per flush; larger inheritance cascades are left to the batch indexer.
- **Measured**: ACL change → `READER` updated in ~1 s.

## R8. Trashcan deletes on ACS 26.2.0 (found during E2E validation)

- **Finding**: The 5.10 pipeline soft-deletes archived nodes (full document, `ALIVE=false`), and the ACS
  26.2.0 query path still returns them. The official 5.7.1 batch indexer writes a stripped tombstone
  instead, which is not searchable. The 5.7.1 reindexing libraries are not published, so downgrading
  is not an option.
- **Decision**: `live.hard-delete-archived=true` (default) deletes the document (id = node uuid) and
  excludes the archive row and the workspace `sys:deleted` tombstone from the job. Set it to `false`
  on ACS versions whose query path filters `ALIVE`.

## R6. Toolchain

- **Decision**: Java 17, Spring Boot 4.1.1 (same as upstream parent), Maven 3.9 + Maven Wrapper,
  JUnit 5 for unit checks.
- **Rationale**: Matching upstream avoids classpath conflicts (Constitution Technology Constraints).
