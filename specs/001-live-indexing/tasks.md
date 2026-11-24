# Tasks: Near-Real-Time Live Indexing for Alfresco Community

**Input**: Design documents from `/specs/001-live-indexing/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/configuration.md

**Tests**: Unit checks required by Constitution VI (parser, pending set). Integration against a live
ACS stack is manual via quickstart.md.

## Phase 1: Setup

- [X] T001 Create Maven project `pom.xml` (Java 17, Spring Boot 4.1.1 parent, Nexus repo, dependency `alfresco-elasticsearch-batch-indexing:5.10.0-A.5`, activemq/actuator/web starters, boot repackage)
- [X] T002 [P] Add Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`)
- [X] T003 [P] Add `.gitignore` for Maven/IDE output

## Phase 2: Foundational

- [X] T004 Create `src/main/java/org/acs/liveindexing/LiveIndexingApp.java` mirroring upstream `BatchIndexingApp` imports, scanning `org.alfresco.elasticsearch.batchindexing` except `OneShotJobRunner`/`BatchIndexingApp`, `@EnableJms`, `@EnableScheduling`
- [X] T005 Create `src/main/resources/application.properties` with `alfresco.reindex.jobName=liveIndex`, pub-sub JMS, `live.*` defaults per contracts/configuration.md
- [X] T006 Create `src/main/java/org/acs/liveindexing/LiveIndexJobConfig.java`: `liveIndex` job + step + `@StepScope` `AlfrescoExplicitIdSetItemReader` reading ids from a holder bean (mirrors upstream `OneShotReindexDlqConfig.reindexDlqStep`)

**Checkpoint**: `./mvnw -DskipTests package` compiles and the Spring context wiring is complete.

## Phase 3: User Story 1 - Documents searchable within seconds (P1) 🎯 MVP

**Goal**: node create/update events → node reindexed via upstream pipeline in ≤ 5 s.

**Independent Test**: quickstart.md scenario 1.

- [X] T007 [P] [US1] Implement `src/main/java/org/acs/liveindexing/NodeEventParser.java` extracting node UUID from data-model.md `Node event.data.resource.id` only when `Node event.data.resource.@type` = `NodeResource`
- [X] T008 [P] [US1] Implement `src/main/java/org/acs/liveindexing/PendingNodes.java` (dedup set, atomic drain) per data-model.md `Pending node set`
- [X] T009 [US1] Implement `src/main/java/org/acs/liveindexing/NodeEventListener.java` (`@JmsListener` on `${live.topic}`) feeding PendingNodes
- [X] T010 [US1] Implement `src/main/java/org/acs/liveindexing/LiveIndexer.java`: scheduled flush every `${live.flush-interval}` → drain → `SELECT id FROM alf_node WHERE uuid IN (...)` (data-model.md `Node batch`) → run `liveIndex` job
- [X] T011 [P] [US1] Unit test `src/test/java/org/acs/liveindexing/NodeEventParserTest.java`
- [X] T012 [P] [US1] Unit test `src/test/java/org/acs/liveindexing/PendingNodesTest.java` (dedup, drain empties, concurrent adds not lost)

## Phase 4: User Story 2 - Permissions respected immediately (P1)

**Goal**: `org.alfresco.event.permission.Updated` triggers reindex with upstream READER/DENIED.

**Independent Test**: quickstart.md scenario 2.

- [X] T013 [US2] Add permission-event case to `src/test/java/org/acs/liveindexing/NodeEventParserTest.java` (parser already accepts any NodeResource event)

## Phase 5: User Story 3 - Deleted documents disappear (P2)

**Goal**: `org.alfresco.event.node.Deleted` → upstream pipeline removes the document (archived row keeps uuid).

**Independent Test**: quickstart.md scenario 3.

- [X] T014 [US3] Add delete-event case to `src/test/java/org/acs/liveindexing/NodeEventParserTest.java`

## Phase 6: User Story 4 - Observable, safe operation (P3)

**Goal**: health, metrics, deployability.

**Independent Test**: quickstart.md scenarios 4–5.

- [X] T015 [US4] Add Micrometer counters (`live.events.received`, `live.events.ignored`, `live.nodes.flushed`, `live.flush.failures`) in `NodeEventListener.java` and `LiveIndexer.java`
- [X] T016 [P] [US4] Create `docker/Dockerfile` (JRE 17, fat jar)
- [X] T017 [P] [US4] Create `docker/compose.live-indexing.yml` overlay service with env per contracts/configuration.md

## Phase 7: Polish

- [X] T018 [P] Write `README.md` (purpose, architecture, config, run, limits)
- [X] T019 Run `./mvnw verify` and fix failures
- [X] T020 Smoke-start the application context without infrastructure to confirm wiring errors are only connection errors

## Phase 8: Findings from E2E validation

- [X] T021 [US2] Track ACL changes via `alf_acl_change_set` in `src/main/java/org/acs/liveindexing/LiveIndexer.java` (Community emits no permission events, research.md R7)
- [X] T022 [US3] Hard-delete trashcan nodes in `src/main/java/org/acs/liveindexing/LiveIndexer.java`, switch `live.hard-delete-archived` (research.md R8)
- [X] T023 [US4] Fix the ACS metadata keystore settings in `docker/compose.live-indexing.yml`, and move the app config to `src/main/resources/config/application.properties` so the upstream defaults still load

## Dependencies & Execution Order

- Setup (T001–T003) → Foundational (T004–T006) → US1 (T007–T012) → US2/US3 (tests only) → US4 → Polish.
- US2 and US3 depend on US1 code (same pipeline); they add verification only.

## Parallel Example: User Story 1

```text
T007 NodeEventParser.java  |  T008 PendingNodes.java  |  T011/T012 tests
```

## Implementation Strategy

MVP = Phases 1–3. US2/US3 come for free from the upstream pipeline; US4 adds ops polish.
