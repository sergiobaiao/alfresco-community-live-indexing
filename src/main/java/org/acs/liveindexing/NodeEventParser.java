package org.acs.liveindexing;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts the node UUID from an {@code alfresco.repo.event2} message. The event is only a trigger:
 * the document is rebuilt from the repository database, so nothing else is read.
 */
final class NodeEventParser
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeEventParser()
    {
    }

    /** Node UUID when {@code data.resource} is a {@code NodeResource} with an id, otherwise empty. */
    static Optional<String> nodeId(String json)
    {
        try
        {
            JsonNode resource = MAPPER.readTree(json).path("data").path("resource");
            String id = resource.path("id").asText("");
            return "NodeResource".equals(resource.path("@type").asText()) && !id.isBlank()
                    ? Optional.of(id)
                    : Optional.empty();
        }
        catch (Exception e)
        {
            return Optional.empty();
        }
    }
}
