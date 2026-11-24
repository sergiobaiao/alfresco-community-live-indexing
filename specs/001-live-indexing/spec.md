# Feature Specification: Near-Real-Time Live Indexing for Alfresco Community

**Feature Branch**: `001-live-indexing`

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "Create a Spring Boot application that performs live indexing for Alfresco
Community 26.x into OpenSearch, complementing the official polling-based batch indexer, reusing the
alfresco-community-search libraries."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - New and updated documents become searchable within seconds (Priority: P1)

A content user uploads or edits a document (name, properties, content) in Alfresco. Seconds later,
searching through Alfresco (Share, ACA, REST search API) finds the document with its new metadata
and full text, instead of waiting for the next batch polling cycle (30–60 s).

**Why this priority**: Reducing search latency is the entire reason the service exists.

**Independent Test**: With the batch indexer stopped, create a document and verify it is returned
by a repository search within the latency target.

**Acceptance Scenarios**:

1. **Given** the service is running and connected, **When** a user creates a document, **Then** the
   document is searchable by name and content through Alfresco within 5 seconds.
2. **Given** an indexed document, **When** a user changes its title, **Then** a search on the new
   title returns it within 5 seconds and the old title no longer matches.
3. **Given** many changes to the same node in a short burst, **When** they arrive, **Then** the node
   is indexed once per burst, reflecting the latest repository state.

---

### User Story 2 - Permissions are respected in search results immediately (Priority: P1)

An administrator removes a user's access to a document. Searches by that user stop returning the
document within seconds. Granting access makes it appear within seconds.

**Why this priority**: Showing documents to users who lost access is a security defect; indexing
without correct permissions is not acceptable.

**Independent Test**: Change a node's permissions directly and verify the indexed reader/denied
authorities match the repository's state.

**Acceptance Scenarios**:

1. **Given** an indexed document readable by user A, **When** A's permission is removed on that node,
   **Then** A's searches stop returning it within 5 seconds.
2. **Given** a node indexed by this service, **When** compared with the same node indexed by the
   official batch indexer, **Then** reader/denied authorities are identical.

---

### User Story 3 - Deleted documents disappear from search (Priority: P2)

A user deletes a document (moves it to the trashcan) and it no longer appears in searches within
seconds.

**Why this priority**: Stale results are confusing, but the batch cycle already removes them, so
this is an improvement, not a blocker.

**Independent Test**: Delete an indexed node and verify the document is removed from the index.

**Acceptance Scenarios**:

1. **Given** an indexed document, **When** it is deleted, **Then** it is no longer returned by
   searches within 5 seconds.

---

### User Story 4 - Operators can observe and run the service safely (Priority: P3)

An operator deploys the service next to the existing stack, checks health, sees how many events
were processed or failed, and can stop it at any time without losing index consistency, because
the official batch indexer keeps repairing the index.

**Why this priority**: Required for production, but does not change search behaviour.

**Independent Test**: Start the service, query the health and metrics endpoints, stop it, verify
the batch indexer still indexes changes.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** the operator queries health, **Then** it reports the
   state of the broker, database and search engine connections.
2. **Given** the service is stopped for an hour, **When** changes happen, **Then** they are still
   indexed by the batch indexer and nothing needs replaying.

---

### Edge Cases

- An event refers to a node that no longer exists in the repository database (purged before
  processing) → nothing is indexed, no error loop; handling matches the batch indexer.
- An event for a node type or aspect excluded by indexing filters → ignored, same as batch indexer.
- Search engine or database temporarily unavailable → events retried with backoff; after retries
  are exhausted, they are logged and dropped (batch indexer repairs later).
- Duplicate or out-of-order events → harmless, because each indexing reads the current state.
- Folder rename or permission inheritance change affecting thousands of descendants → only the
  directly changed node is indexed live; descendants are repaired by the batch indexer cycle.
- Broker connection lost → service reconnects automatically; events published while disconnected
  may be lost unless a durable subscription is used.
- Node from a custom content model whose namespace prefix is unknown → handled exactly like the
  batch indexer (same prefix map configuration).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST subscribe to the repository's node event stream and react to node
  created, updated and deleted events, and MUST detect permission changes (which Community does not
  publish as events) from the repository's permission change records.
- **FR-002**: System MUST treat events only as triggers and MUST read the node's current state
  (metadata, aspects, permissions, path, content reference) from the repository before indexing.
- **FR-003**: System MUST produce index documents identical to the official batch indexer for the
  same repository state (metadata, reader/denied authorities, path, full text).
- **FR-004**: System MUST coalesce multiple events for the same node received within a short
  window into one indexing operation.
- **FR-005**: System MUST remove a node's documents from the index when the node is deleted or no
  longer exists.
- **FR-006**: System MUST apply the same node type, aspect and field filters as the batch indexer.
- **FR-007**: System MUST retry transient failures with bounded backoff and MUST NOT block other
  nodes while retrying.
- **FR-008**: System MUST NOT modify the batch indexer's progress state, the repository database or
  repository content.
- **FR-009**: System MUST expose health and processing metrics (events received, nodes indexed,
  nodes deleted, failures).
- **FR-010**: System MUST be configurable entirely through external configuration (broker,
  database, search engine, repository URL, shared secret, coalescing window).
- **FR-011**: System MUST allow content (full-text) indexing to be disabled, keeping metadata,
  permission and path indexing.

### Key Entities

- **Node event**: Notification that a repository node changed; carries the node identifier and
  change type. Not trusted as document content.
- **Node batch**: Set of distinct node identifiers collected during a coalescing window.
- **Index document**: Searchable representation of a node (metadata, authorities, path, content)
  in the shared search index.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of document creations/updates are searchable within 5 seconds under normal load
  (versus 30–60 seconds with the batch indexer only).
- **SC-002**: 100% of permission removals are reflected in search results within 5 seconds for the
  directly changed node.
- **SC-003**: For a sample of 100 nodes, documents written by this service match documents written
  by the batch indexer field-for-field.
- **SC-004**: Stopping the service for any duration causes no permanent index inconsistency once
  the batch indexer completes its next cycle.
- **SC-005**: A burst of 1,000 changes on 100 nodes results in at most 100 indexing operations per
  coalescing window.

## Assumptions

- Target platform is Alfresco Community 26.2+ with the `elasticsearch` search subsystem, OpenSearch
  2.11.x, and the official batch indexer deployed.
- The repository publishes node events to the message broker (event publishing enabled).
- The service has read-only access to the repository database and the shared secret for the text
  extraction endpoint.
- Cascading updates (descendants after folder rename/ACL inheritance change) are out of scope for
  v1 and left to the batch indexer.
- Initial/historical indexing is out of scope; the batch indexer owns it.
- Single instance deployment is sufficient for v1.
