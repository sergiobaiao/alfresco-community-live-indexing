<!--
Sync Impact Report
- Version change: template → 1.0.0
- Principles added: I. Reuse Upstream Indexing, II. Index Contract Fidelity, III. Batch Indexer Is
  The Safety Net, IV. Read-Only Repository Access, V. Minimal Code, VI. One Runnable Check Per Logic
- Sections added: Technology Constraints, Development Workflow
- Removed sections: none
- Deferred TODOs: none
-->

# Alfresco Community Live Indexing Constitution

## Core Principles

### I. Reuse Upstream Indexing

Index documents MUST be produced by the libraries published from
`Alfresco/alfresco-community-search` (`alfresco-search-reindexing-core`,
`alfresco-search-opensearch-client`, `alfresco-search-index-model`). The project MUST NOT
re-implement field encoding, ACL (`READER`/`DENIED`) calculation, path building or content
fetching that those libraries already provide.
Rationale: the repository's `elasticsearch` subsystem queries a schema owned upstream; a divergent
copy silently breaks search.

### II. Index Contract Fidelity

A node indexed by this service MUST produce the same document as the official batch indexer for
the same repository state. Event payloads are triggers only: document content MUST be read from
the repository database, because Community `alfresco.repo.event2` events carry no reader/denied
authorities.

### III. Batch Indexer Is The Safety Net

The official `alfresco-elasticsearch-batch-indexing` keeps running. This service MUST NOT write
the batch indexer's watermark document, MUST NOT replace historical/initial indexing, and MAY leave
cascades (folder rename, ACL inheritance) to the batch cycle. Lost events are tolerated because
the batch cycle repairs them.

### IV. Read-Only Repository Access

The service MUST use read-only database credentials and MUST NOT write to the repository database
or call mutating repository APIs. Secrets (DB password, shared secret, broker credentials) MUST
come from configuration/environment, never from source.

### V. Minimal Code

Smallest working design wins: no abstractions with a single implementation, no speculative
configuration, no features beyond the spec. Deliberate simplifications carry a `ponytail:`
comment naming the ceiling and upgrade path.

### VI. One Runnable Check Per Logic

Every non-trivial branch, loop or parser MUST leave one runnable automated check (JUnit) that fails
if the logic breaks. Infrastructure-heavy integration tests are optional and MUST NOT block the
default build.

## Technology Constraints

- Java 17, Spring Boot version aligned with `alfresco-community-search` (4.1.x), Maven build.
- Messaging: ActiveMQ (Classic) via JMS, topic `alfresco.repo.event2`.
- Search engine: OpenSearch 2.11.x (primary) or Elasticsearch 8.17.x through the upstream client.
- Target: Alfresco Community 26.2+ with `index.subsystem.name=elasticsearch`.

## Development Workflow

- Spec Kit flow: constitution → specify → plan → tasks → implement.
- `mvn verify` MUST pass with unit tests before a change is considered done.
- Changes to the upstream library versions MUST be re-verified against the index contract.

## Governance

This constitution supersedes other practices for this repository. Amendments require updating this
file, bumping the version (MAJOR: principle removed/redefined; MINOR: principle/section added;
PATCH: wording) and noting the change in the Sync Impact Report. Plans MUST include a
Constitution Check against Principles I–VI.

**Version**: 1.0.0 | **Ratified**: 2026-11-24 | **Last Amended**: 2026-11-24
