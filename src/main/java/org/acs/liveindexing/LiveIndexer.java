package org.acs.liveindexing;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every flush window: pending UUIDs + nodes whose ACL changed → repository dbIds → one {@code liveIndex}
 * job run. Trashcan deletes are removed from the index directly (see {@link #hardDeleteArchived}).
 */
@Component
class LiveIndexer
{
    private static final Logger LOG = LoggerFactory.getLogger(LiveIndexer.class);
    private static final int MAX_IN_CLAUSE = 1000;
    private static final int MAX_ACL_NODES = 10_000;

    private final PendingNodes pending;
    private final NamedParameterJdbcTemplate jdbc;
    private final JobLauncher jobLauncher;
    private final Job liveIndex;
    private final AtomicReference<Set<Long>> targetIds;
    private final OpenSearchClient openSearch;
    private final String indexName;
    private final boolean hardDeleteArchived;
    private final Counter flushed;
    private final Counter deleted;
    private final Counter failures;
    private Long lastAclCommitMs; // flusher thread only

    LiveIndexer(PendingNodes pending, JdbcTemplate repositoryJdbc, JobLauncher jobLauncher, Job liveIndex,
            AtomicReference<Set<Long>> liveIndexTargetIds, OpenSearchClient openSearch,
            @Value("${elasticsearch.indexName}") String indexName,
            @Value("${live.hard-delete-archived}") boolean hardDeleteArchived,
            MeterRegistry registry)
    {
        this.pending = pending;
        this.jdbc = new NamedParameterJdbcTemplate(repositoryJdbc); // same JdbcTemplate upstream reads nodes with
        this.jobLauncher = jobLauncher;
        this.liveIndex = liveIndex;
        this.targetIds = liveIndexTargetIds;
        this.openSearch = openSearch;
        this.indexName = indexName;
        this.hardDeleteArchived = hardDeleteArchived;
        this.flushed = registry.counter("live.nodes.flushed");
        this.deleted = registry.counter("live.nodes.deleted");
        this.failures = registry.counter("live.flush.failures");
    }

    // ponytail: one job execution per non-empty flush in the upstream in-memory job repository, so
    // memory grows with uptime (upstream recommends periodic restarts for the same reason). Upgrade:
    // resourceless job repository or calling reader/processor/writer directly.
    @Scheduled(fixedDelayString = "${live.flush-interval}")
    void flush()
    {
        Set<String> uuids = pending.drain();
        try
        {
            uuids.addAll(aclChangedNodes());
            if (uuids.isEmpty())
            {
                return;
            }
            Map<String, Set<Long>> rows = nodeRows(uuids);
            Set<Long> ids = new HashSet<>(rows.getOrDefault("workspace", Set.of()));
            if (hardDeleteArchived)
            {
                ids.removeAll(hardDeleteArchived(rows.getOrDefault("archive", Set.of())));
            }
            else
            {
                ids.addAll(rows.getOrDefault("archive", Set.of()));
            }
            if (ids.isEmpty())
            {
                return;
            }
            targetIds.set(ids);
            jobLauncher.run(liveIndex, new JobParametersBuilder()
                    .addLong("run.id", System.nanoTime())
                    .toJobParameters());
            flushed.increment(ids.size());
        }
        catch (Exception e)
        {
            // Dropped on purpose: the batch indexer's next cycle covers these nodes.
            failures.increment();
            LOG.warn("Live indexing of {} nodes failed, leaving them to the batch indexer", uuids.size(), e);
        }
    }

    /**
     * Community ACS emits no permission events, so ACL changes are tracked like Solr did: new rows in
     * {@code alf_acl_change_set}. First call only records the current position.
     */
    private Set<String> aclChangedNodes()
    {
        if (lastAclCommitMs == null)
        {
            Long max = jdbc.getJdbcTemplate().queryForObject("SELECT MAX(commit_time_ms) FROM alf_acl_change_set", Long.class);
            lastAclCommitMs = max == null ? 0L : max;
            return new HashSet<>();
        }
        Set<String> uuids = new HashSet<>();
        long[] max = {lastAclCommitMs};
        // ponytail: no overlap, a change set committing with an older timestamp than one already seen
        // is missed (batch indexer repairs it). Capped so a huge inheritance cascade can't flood us.
        jdbc.query("""
                SELECT n.uuid, c.commit_time_ms FROM alf_node n
                JOIN alf_access_control_list a ON n.acl_id = a.id
                JOIN alf_acl_change_set c ON a.acl_change_set = c.id
                WHERE c.commit_time_ms > :since ORDER BY c.commit_time_ms LIMIT %d""".formatted(MAX_ACL_NODES),
                Map.of("since", lastAclCommitMs),
                rs -> {
                    uuids.add(rs.getString(1));
                    max[0] = Math.max(max[0], rs.getLong(2));
                });
        if (uuids.size() >= MAX_ACL_NODES)
        {
            LOG.warn("ACL change touched >= {} nodes; the rest is left to the batch indexer", MAX_ACL_NODES);
        }
        lastAclCommitMs = max[0];
        return uuids;
    }

    /** Store protocol ({@code workspace}/{@code archive}) → dbIds for the given UUIDs. */
    private Map<String, Set<Long>> nodeRows(Set<String> uuids)
    {
        Map<String, Set<Long>> rows = new java.util.HashMap<>();
        List<String> all = List.copyOf(uuids);
        for (int i = 0; i < all.size(); i += MAX_IN_CLAUSE)
        {
            jdbc.query("SELECT n.id, s.protocol FROM alf_node n JOIN alf_store s ON n.store_id = s.id WHERE n.uuid IN (:uuids)",
                    Map.of("uuids", all.subList(i, Math.min(i + MAX_IN_CLAUSE, all.size()))),
                    rs -> {
                        rows.computeIfAbsent(rs.getString(2), k -> new HashSet<>()).add(rs.getLong(1));
                    });
        }
        return rows;
    }

    /**
     * ACS 26.2.0's query path does not filter {@code ALIVE=false}, and the official 5.7.1 batch indexer
     * removes trashcan nodes. The 5.10 pipeline soft-deletes them instead, so they would stay searchable:
     * delete the document (id = node uuid) and drop the node's rows (archive + workspace tombstone) from
     * the job. Returns the workspace dbIds sharing those uuids, to exclude.
     */
    private Set<Long> hardDeleteArchived(Set<Long> archiveIds) throws java.io.IOException
    {
        if (archiveIds.isEmpty())
        {
            return Set.of();
        }
        List<String> archivedUuids = jdbc.queryForList("SELECT uuid FROM alf_node WHERE id IN (:ids)",
                Map.of("ids", archiveIds), String.class);
        var bulk = new BulkRequest.Builder();
        archivedUuids.forEach(uuid -> bulk.operations(op -> op.delete(d -> d.index(indexName).id(uuid))));
        openSearch.bulk(bulk.build());
        deleted.increment(archivedUuids.size());
        return new HashSet<>(jdbc.queryForList("SELECT id FROM alf_node WHERE uuid IN (:uuids)",
                Map.of("uuids", archivedUuids), Long.class));
    }
}
