# Implementation Plan: Near-Real-Time Live Indexing for Alfresco Community

**Branch**: `001-live-indexing` | **Date**: 2026-11-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-live-indexing/spec.md`

## Summary

Spring Boot service that subscribes to ACS `alfresco.repo.event2`, coalesces node UUIDs for ~1 s,
resolves them to repository dbIds and runs the upstream Alfresco Search Community indexing pipeline
(`AlfrescoExplicitIdSetItemReader` → `processors` → `writer`) for just those nodes. Documents are
therefore identical to the official batch indexer, which keeps running as safety net. See
[research.md](research.md).

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Spring Boot 4.1.1 (web, actuator, activemq, jdbc, batch via upstream),
`org.alfresco:alfresco-elasticsearch-batch-indexing:5.10.0-A.5` (+ transitive reindexing-core,
opensearch-client, index-model, database-connector)

**Storage**: None owned. Reads ACS DB (read-only); writes OpenSearch index via upstream writer.

**Testing**: JUnit 5 (spring-boot-starter-test), unit checks for parsing and coalescing

**Target Platform**: Linux container (Docker), alongside ACS Community 26.2+

**Project Type**: Single backend service

**Performance Goals**: p95 change → searchable ≤ 5 s; burst of 1,000 events on 100 nodes → ≤ 100
node indexings per window

**Constraints**: Single instance; must not write watermark; read-only DB user

**Scale/Scope**: One repository, one index; cascades out of scope (batch indexer)

## Constitution Check

| Principle | Status |
|-----------|--------|
| I. Reuse Upstream Indexing | PASS — upstream processors/writer/reader reused, no encoder code |
| II. Index Contract Fidelity | PASS — state read from DB; event = trigger only |
| III. Batch Indexer Safety Net | PASS — `jobName=liveIndex` removes watermark beans (R3) |
| IV. Read-Only Repository Access | PASS — only SELECTs; secrets via env |
| V. Minimal Code | PASS — ~5 classes; ceiling of job repository noted (R5) |
| VI. One Runnable Check | PASS — unit tests for parser and pending set |

Post-design re-check: PASS, no violations.

## Project Structure

### Documentation (this feature)

```text
specs/001-live-indexing/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/configuration.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
pom.xml
mvnw, mvnw.cmd, .mvn/wrapper/
src/main/java/org/acs/liveindexing/
├── LiveIndexingApp.java        # Boot app; wires upstream configs, excludes OneShotJobRunner
├── NodeEventParser.java        # event JSON → node UUID (or empty)
├── PendingNodes.java           # concurrent dedup set with atomic drain
├── NodeEventListener.java      # @JmsListener on alfresco.repo.event2
├── LiveIndexJobConfig.java     # liveIndex job/step/reader (mirrors upstream DLQ step)
└── LiveIndexer.java            # scheduled flush: drain → dbIds → run job; metrics
src/main/resources/application.properties
src/test/java/org/acs/liveindexing/
├── NodeEventParserTest.java
└── PendingNodesTest.java
docker/Dockerfile
docker/compose.live-indexing.yml
README.md
```

**Structure Decision**: Single Maven module; flat package, no layering — the heavy lifting is
upstream.

## Complexity Tracking

None.
