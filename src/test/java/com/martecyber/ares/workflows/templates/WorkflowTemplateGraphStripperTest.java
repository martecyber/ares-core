package com.martecyber.ares.workflows.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTemplateGraphStripperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String graphWithOneNode(String type, String configJson) {
        return "{\"nodes\":[{\"id\":\"n1\",\"type\":\"" + type + "\",\"position\":{\"x\":10,\"y\":20},"
            + "\"data\":{\"label\":\"My node\",\"config\":" + configJson + "}}],\"edges\":[]}";
    }

    @Test
    void stripsPoolIdFromAgentTaskNodes() throws Exception {
        String stripped = WorkflowTemplateGraphStripper.strip(
            graphWithOneNode("ACTION_AGENT_TASK", "{\"poolId\":7,\"tool\":\"nmap\"}"));
        JsonNode config = configOf(stripped);
        assertFalse(config.has("poolId"));
        assertEquals("nmap", config.get("tool").asText());
    }

    @Test
    void stripsIntegrationIdFromNotificationNodes() throws Exception {
        String stripped = WorkflowTemplateGraphStripper.strip(
            graphWithOneNode("ACTION_NOTIFICATION", "{\"integrationId\":3,\"template\":\"hi\"}"));
        assertFalse(configOf(stripped).has("integrationId"));
    }

    @Test
    void stripsIntegrationIdFromSyncNodes() throws Exception {
        String stripped = WorkflowTemplateGraphStripper.strip(
            graphWithOneNode("ACTION_SYNC", "{\"integrationId\":9}"));
        assertFalse(configOf(stripped).has("integrationId"));
    }

    @Test
    void stripsIntegrationIdFromIntegrationCallNodesButKeepsPortableFields() throws Exception {
        String stripped = WorkflowTemplateGraphStripper.strip(
            graphWithOneNode("ACTION_INTEGRATION_CALL",
                "{\"integrationId\":5,\"integrationType\":\"caido-api\",\"action\":\"start-scan\",\"params\":{\"target\":\"{{trigger.entityId}}\"}}"));
        JsonNode config = configOf(stripped);
        assertFalse(config.has("integrationId"));
        assertEquals("caido-api", config.get("integrationType").asText());
        assertEquals("start-scan", config.get("action").asText());
        assertEquals("{{trigger.entityId}}", config.get("params").get("target").asText());
    }

    @Test
    void leavesUnrelatedNodeTypesCompletelyUntouched() throws Exception {
        String original = graphWithOneNode("CONDITION", "{\"aql\":\"priority >= 3\"}");
        String stripped = WorkflowTemplateGraphStripper.strip(original);
        assertEquals(MAPPER.readTree(original), MAPPER.readTree(stripped));
    }

    @Test
    void preservesNodePositionAndLabelAndEdges() throws Exception {
        String original = "{\"nodes\":[{\"id\":\"n1\",\"type\":\"ACTION_AGENT_TASK\",\"position\":{\"x\":1,\"y\":2},"
            + "\"data\":{\"label\":\"Scan\",\"config\":{\"poolId\":1}}}],"
            + "\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"sourceHandle\":\"success\"}]}";
        JsonNode strippedRoot = MAPPER.readTree(WorkflowTemplateGraphStripper.strip(original));
        JsonNode node = strippedRoot.get("nodes").get(0);
        assertEquals(1, node.get("position").get("x").asInt());
        assertEquals(2, node.get("position").get("y").asInt());
        assertEquals("Scan", node.get("data").get("label").asText());
        assertEquals(1, strippedRoot.get("edges").size());
        assertEquals("success", strippedRoot.get("edges").get(0).get("sourceHandle").asText());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowTemplateGraphStripper.strip("not json"));
    }

    private static JsonNode configOf(String graphJson) throws Exception {
        return MAPPER.readTree(graphJson).get("nodes").get(0).get("data").get("config");
    }
}
