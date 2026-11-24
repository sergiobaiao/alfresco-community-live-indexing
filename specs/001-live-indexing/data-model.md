# Data Model: Near-Real-Time Live Indexing

No owned persistent storage. All entities are transient or owned upstream.

## Node event (input, owned by ACS)

JSON CloudEvent on topic `alfresco.repo.event2`. Fields used:

| Field | Use |
|-------|-----|
| `type` | Logged only (`org.alfresco.event.node.*`, `org.alfresco.event.permission.Updated`) |
| `data.resource.@type` | Must be `NodeResource`, otherwise ignored |
| `data.resource.id` | Node UUID → trigger |

Validation: missing/blank id, non-JSON, or non-node resource → ignored and counted.

## Pending node set (in memory)

- Set of node UUIDs; duplicates collapse.
- Lifecycle: `add` (listener thread) → `drain` (flusher, atomic swap) → empty.

## Node batch (per flush)

- `Set<Long>` repository dbIds resolved from the drained UUIDs via `alf_node`.
- UUIDs without a row are dropped (nothing to index).

## Index documents (output, owned upstream)

Written by the upstream `writer` into index `elasticsearch.indexName` (default `alfresco`):
metadata, `READER`/`DENIED`, path and content fields. Schema defined by `alfresco-search-index-model`
and the repository's `ContentModelSynchronizer`; this project defines none of it.
