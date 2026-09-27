package com.martecyber.ares.workflows;

import org.junit.jupiter.api.Test;

import com.martecyber.ares.integrations.notifications.MessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowContextFlattenerTest {

    @Test
    void flattensNestedObjectsIntoDottedKeys() {
        Map<String, Object> flat = WorkflowContextFlattener.flatten(
            "{\"trigger\":{\"entityId\":42},\"steps\":{\"lookupStep\":{\"output\":{\"title\":\"XSS\",\"score\":7.5,\"kev\":true}}}}");
        assertEquals(42L, ((Number) flat.get("trigger.entityId")).longValue());
        assertEquals("XSS", flat.get("steps.lookupStep.output.title"));
        assertEquals(7.5, ((Number) flat.get("steps.lookupStep.output.score")).doubleValue());
        assertEquals(true, flat.get("steps.lookupStep.output.kev"));
    }

    @Test
    void emptyOrBlankContextFlattensToEmptyMap() {
        assertTrue(WorkflowContextFlattener.flatten("").isEmpty());
        assertTrue(WorkflowContextFlattener.flatten("{}").isEmpty());
    }

    @Test
    void flattenedContextRendersThroughMessagingTemplate() {
        Map<String, Object> flat = WorkflowContextFlattener.flatten(
            "{\"steps\":{\"lookupStep\":{\"output\":{\"title\":\"XSS\"}}}}");
        String rendered = MessagingTemplate.render("Found: {{steps.lookupStep.output.title}}", flat);
        assertEquals("Found: XSS", rendered);
    }

    @Test
    void plainNonDottedTemplatesStillWorkUnaffected() {
        String rendered = MessagingTemplate.render("Hello {{name}}", Map.of("name", "World"));
        assertEquals("Hello World", rendered);
    }

    @Test
    void arraysFlattenWithNumericIndexSuffixes() {
        Map<String, Object> flat = WorkflowContextFlattener.flatten("{\"trigger\":{\"tags\":[\"a\",\"b\"]}}");
        assertEquals("a", flat.get("trigger.tags.0"));
        assertEquals("b", flat.get("trigger.tags.1"));
    }
}
