package org.acs.liveindexing;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Component;

/** Node UUIDs waiting for the next flush; duplicates within a window collapse into one indexing. */
@Component
class PendingNodes
{
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    void add(String nodeId)
    {
        queue.add(nodeId);
    }

    /** Removes and returns the distinct UUIDs queued so far; concurrent adds go to this or the next drain. */
    Set<String> drain()
    {
        Set<String> ids = new HashSet<>();
        for (String id; (id = queue.poll()) != null; )
        {
            ids.add(id);
        }
        return ids;
    }
}
