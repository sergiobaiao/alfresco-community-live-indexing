package org.acs.liveindexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class NodeEventParserTest
{
    private static String event(String type, String resourceType, String id)
    {
        return """
                {"specversion":"1.0","type":"%s","id":"e1","source":"/repo",
                 "data":{"eventGroupId":"g1","resource":{"@type":"%s","id":"%s","name":"a.txt"}}}
                """.formatted(type, resourceType, id);
    }

    @Test
    void nodeCreatedAndUpdatedYieldNodeId()
    {
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.node.Created", "NodeResource", "n1"))).contains("n1");
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.node.Updated", "NodeResource", "n2"))).contains("n2");
    }

    @Test
    void permissionUpdatedYieldsNodeId()
    {
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.permission.Updated", "NodeResource", "n3"))).contains("n3");
    }

    @Test
    void nodeDeletedYieldsNodeId()
    {
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.node.Deleted", "NodeResource", "n4"))).contains("n4");
    }

    @Test
    void nonNodeResourcesBlankIdsAndGarbageAreIgnored()
    {
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.assoc.child.Created", "ChildAssociationResource", "c1"))).isEmpty();
        assertThat(NodeEventParser.nodeId(event("org.alfresco.event.node.Created", "NodeResource", " "))).isEmpty();
        assertThat(NodeEventParser.nodeId("not json")).isEqualTo(Optional.empty());
        assertThat(NodeEventParser.nodeId("{}")).isEmpty();
    }
}
