package org.acs.liveindexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PendingNodesTest
{
    @Test
    void duplicatesCollapseAndDrainStartsNewWindow()
    {
        var pending = new PendingNodes();
        for (int i = 0; i < 1000; i++)
        {
            pending.add("n" + (i % 100));
        }
        assertThat(pending.drain()).hasSize(100);
        assertThat(pending.drain()).isEmpty();
    }

    @Test
    void concurrentAddsAreNotLostAcrossDrains() throws Exception
    {
        var pending = new PendingNodes();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++)
        {
            int base = t * 10_000;
            pool.submit(() -> {
                for (int i = 0; i < 10_000; i++)
                {
                    pending.add("n" + (base + i));
                }
            });
        }
        pool.shutdown();
        Set<String> seen = new HashSet<>();
        while (!pool.awaitTermination(1, TimeUnit.MILLISECONDS))
        {
            seen.addAll(pending.drain());
        }
        seen.addAll(pending.drain());
        assertThat(seen).hasSize(40_000);
    }
}
