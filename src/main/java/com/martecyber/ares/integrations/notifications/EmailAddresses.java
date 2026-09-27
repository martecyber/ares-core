package com.martecyber.ares.integrations.notifications;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared To/CC/BCC handling for every email-sending call site (Workflows' {@code
 * ACTION_NOTIFICATION} node, {@code FindingEmailReportService}) — renders a raw, comma/
 * semicolon/newline-separated address list (each entry may itself be a {@code {{var}}} template)
 * against a flat variable map, then validates every resulting address. A bad address fails the
 * whole call with a clear message instead of a silent partial send.
 */
public final class EmailAddresses {

    private EmailAddresses() {}

    /** @throws IllegalArgumentException naming the invalid entries — callers translate to
     *  whatever exception type fits their context (a workflow step failure, a 400 response, …). */
    public static List<String> renderAndValidate(String fieldName, String raw, Map<String, Object> vars) {
        if (raw == null || raw.isBlank()) return List.of();
        String rendered = MessagingTemplate.render(raw, vars);
        List<String> out = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String part : rendered.split("[,;\\r\\n]+")) {
            String addr = part.trim();
            if (addr.isEmpty()) continue;
            try {
                new InternetAddress(addr, true);
                out.add(addr);
            } catch (AddressException e) {
                invalid.add(addr);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(fieldName + ": invalid address(es): " + invalid);
        }
        return out;
    }
}
