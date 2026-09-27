package com.martecyber.ares.messaging;

import org.junit.jupiter.api.Test;

import com.martecyber.ares.integrations.notifications.MessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagingTemplateTest {

    @Test
    void substitutesASimpleVariable() {
        assertEquals("hello world", MessagingTemplate.render("hello {{name}}", Map.of("name", "world")));
    }

    @Test
    void tolerantOfInnerWhitespace() {
        assertEquals("hello world", MessagingTemplate.render("hello {{ name }}", Map.of("name", "world")));
    }

    @Test
    void unknownVariableCollapsesToEmptyString() {
        assertEquals("hello ", MessagingTemplate.render("hello {{missing}}", Map.of()));
    }

    @Test
    void substitutesADottedVariable() {
        assertEquals("critical", MessagingTemplate.render("{{trigger.entity.severity}}",
            Map.of("trigger.entity.severity", "critical")));
    }

    /** Regression test: workflow editor node ids are generated as
     *  {@code <type>-<timestamp>-<seq>} (see WorkflowCanvas.vue), so a step-output reference like
     *  {@code {{steps.action_agent_task-1755300000000-3.output.exitCode}}} is what the "Available
     *  here" picker actually inserts into a notification template. Before this fix, VAR's
     *  character class didn't include '-', so the whole placeholder silently failed to match and
     *  was delivered to the user as unrendered literal text — reported bug. */
    @Test
    void substitutesAHyphenatedStepReference() {
        String rendered = MessagingTemplate.render(
            "exit code: {{steps.action_agent_task-1755300000000-3.output.exitCode}}",
            Map.of("steps.action_agent_task-1755300000000-3.output.exitCode", "0"));
        assertEquals("exit code: 0", rendered);
    }

    @Test
    void multipleHyphenatedReferencesInOneTemplate() {
        String rendered = MessagingTemplate.render(
            "{{steps.node-a-1.output}} then {{steps.node-b-2.output}}",
            Map.of("steps.node-a-1.output", "first", "steps.node-b-2.output", "second"));
        assertEquals("first then second", rendered);
    }

    @Test
    void renderHtmlSafeEscapesSubstitutedValueOnly() {
        String rendered = MessagingTemplate.renderHtmlSafe(
            "<p>Detection: {{title}}</p>", Map.of("title", "<script>alert(1)</script> & \"co\""));
        assertEquals("<p>Detection: &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;co&quot;</p>", rendered);
    }

    @Test
    void renderHtmlSafeLeavesTemplateMarkupUntouched() {
        String rendered = MessagingTemplate.renderHtmlSafe(
            "<table><tr><td>{{name}}</td></tr></table>", Map.of("name", "Asset's <owner>"));
        assertEquals("<table><tr><td>Asset&#39;s &lt;owner&gt;</td></tr></table>", rendered);
    }

    @Test
    void blockRepeatsOncePerListElement() {
        String rendered = MessagingTemplate.render("{{#items}}[{{code}}]{{/items}}",
            Map.of("items", List.of(Map.of("code", "A"), Map.of("code", "B"), Map.of("code", "C"))));
        assertEquals("[A][B][C]", rendered);
    }

    @Test
    void blockCollapsesToEmptyWhenTheValueIsMissing() {
        String rendered = MessagingTemplate.render("before{{#items}}[{{code}}]{{/items}}after", Map.of());
        assertEquals("beforeafter", rendered);
    }

    @Test
    void blockCollapsesToEmptyWhenTheListIsEmpty() {
        String rendered = MessagingTemplate.render("before{{#items}}[{{code}}]{{/items}}after",
            Map.of("items", List.of()));
        assertEquals("beforeafter", rendered);
    }

    @Test
    void blockCollapsesToEmptyWhenTheValueIsNotAList() {
        String rendered = MessagingTemplate.render("before{{#items}}[{{code}}]{{/items}}after",
            Map.of("items", "not a list"));
        assertEquals("beforeafter", rendered);
    }

    @Test
    void blockElementFieldsFallBackToTheOuterContext() {
        String rendered = MessagingTemplate.render("{{#items}}{{finding.title}}:{{code}} {{/items}}",
            Map.of("finding.title", "SQLi", "items", List.of(Map.of("code", "A"), Map.of("code", "B"))));
        assertEquals("SQLi:A SQLi:B ", rendered);
    }

    @Test
    void blockElementFieldsShadowSameNamedOuterKeys() {
        String rendered = MessagingTemplate.render("{{#items}}{{code}} {{/items}}",
            Map.of("code", "OUTER", "items", List.of(Map.of("code", "INNER"))));
        assertEquals("INNER ", rendered);
    }

    @Test
    void blocksNest() {
        Map<String, Object> asset1 = Map.of("identifier", "host-1");
        Map<String, Object> asset2 = Map.of("identifier", "host-2");
        Map<String, Object> affection = Map.of("code", "AFF-1", "affects", List.of(asset1, asset2));
        String rendered = MessagingTemplate.render(
            "{{#affections}}[{{code}}:{{#affects}}{{identifier}},{{/affects}}]{{/affections}}",
            Map.of("affections", List.of(affection)));
        assertEquals("[AFF-1:host-1,host-2,]", rendered);
    }

    @Test
    void renderHtmlSafeEscapesLeafValuesInsideABlock() {
        String rendered = MessagingTemplate.renderHtmlSafe("{{#items}}<td>{{title}}</td>{{/items}}",
            Map.of("items", List.of(Map.of("title", "<script>x</script>"))));
        assertEquals("<td>&lt;script&gt;x&lt;/script&gt;</td>", rendered);
    }
}
