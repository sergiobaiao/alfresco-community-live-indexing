package org.acs.liveindexing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
class NodeEventListener
{
    private final PendingNodes pending;
    private final Counter received;
    private final Counter ignored;

    NodeEventListener(PendingNodes pending, MeterRegistry registry)
    {
        this.pending = pending;
        this.received = registry.counter("live.events.received");
        this.ignored = registry.counter("live.events.ignored");
    }

    // ponytail: non-durable topic subscription; events published while disconnected are repaired by
    // the batch indexer. Durable subscription needs a fixed client id, add if gaps matter.
    @JmsListener(destination = "${live.topic}")
    void onEvent(String json)
    {
        received.increment();
        NodeEventParser.nodeId(json).ifPresentOrElse(pending::add, ignored::increment);
    }
}
