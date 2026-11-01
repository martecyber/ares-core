package com.martecyber.ares.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.IpValidator;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type + identifier validation for manually entered assets (org "New asset" and project
 * "Add asset"). Returns the identifier in its canonical form so duplicate checks and later
 * import dedup (exact (org, type, identifier) match) line up with what parsers produce.
 *
 * Importers are deliberately NOT routed through here: parser output is already normalized
 * by the parsers themselves, and rejecting a scan row would lose data rather than prevent a typo.
 *
 * Every failure is an {@link IllegalArgumentException} (→ 422 via GlobalExceptionHandler).
 */
public final class AssetIdentifierRules {

    /**
     * Manual-entry limit for most types. The column itself is TEXT (V211) so imports never
     * lose a value; this only keeps hand-typed names sane (a DNS name is ≤ 253 anyway).
     */
    public static final int MAX_LENGTH = 255;

    /** DNS TXT values: DKIM 4096-bit keys and long SPF chains run to ~1k characters. */
    public static final int TEXT_DATA_MAX_LENGTH = 4096;

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$");
    private static final Pattern SERVICE   = Pattern.compile("^(.+):(\\d{1,5})/([A-Za-z0-9]+)$");
    private static final Pattern CONTROL   = Pattern.compile("\\p{Cntrl}");

    private AssetIdentifierRules() {}

    /** Throws unless {@code type} is one of {@link AssetType#ALL}. */
    public static void requireKnownType(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Asset type is required");
        if (!AssetType.ALL.contains(type)) throw new IllegalArgumentException("Unknown asset type '" + type + "'");
    }

    /** Validates {@code identifier} for {@code type} and returns its canonical form. */
    public static String normalize(String type, String identifier) {
        requireKnownType(type);
        String v = identifier == null ? "" : identifier.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Identifier is required");
        if (CONTROL.matcher(v).find()) throw new IllegalArgumentException("Identifier contains control characters");

        String out = switch (type) {
            case AssetType.IP              -> ip(v);
            case AssetType.NETWORK         -> cidr(v);
            case AssetType.DOMAIN          -> domain(v);
            case AssetType.EMAIL           -> email(v);
            case AssetType.SERVICE         -> service(v);
            case AssetType.WEB_APPLICATION,
                 AssetType.WEB_ENDPOINT    -> url(v);
            default                        -> v; // free-form names (host, technology, person, …)
        };
        int max = maxLength(type);
        if (out.length() > max) {
            throw new IllegalArgumentException("Identifier is too long (max " + max + " characters)");
        }
        return out;
    }

    public static final int DISPLAY_NAME_MAX_LENGTH = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Hand-entered metadata must be a JSON object (the column is jsonb and every reader expects
     * an object). Blank means "no metadata". Anything else is a 422, not a database error.
     */
    public static String normalizeMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return null;
        JsonNode node;
        try {
            node = JSON.readTree(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("Metadata must be valid JSON");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Metadata must be a JSON object");
        return metadata;
    }

    /** Trims a user-entered display name; blank → null (clears it). */
    public static String normalizeDisplayName(String displayName) {
        if (displayName == null) return null;
        String v = displayName.trim();
        if (v.isEmpty()) return null;
        if (CONTROL.matcher(v).find()) throw new IllegalArgumentException("Display name contains control characters");
        if (v.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Display name is too long (max " + DISPLAY_NAME_MAX_LENGTH + " characters)");
        }
        return v;
    }

    /**
     * Whether a user may change an existing asset's identifier. It is the import dedup key, so
     * only types whose imports recognize them by something else allow it: HOST (matched through
     * its interfaces; identifier is the user-owned Name) and WEB_APPLICATION (identifier is the
     * primary Base URL, and imports also match any of its Base URLs).
     */
    public static boolean identifierEditable(String type) {
        return AssetType.HOST.equals(type) || AssetType.WEB_APPLICATION.equals(type);
    }

    /** Manual-entry length limit for {@code type}. */
    public static int maxLength(String type) {
        return AssetType.TEXT_DATA.equals(type) ? TEXT_DATA_MAX_LENGTH : MAX_LENGTH;
    }

    private static String ip(String v) {
        if (!IpValidator.isValidLiteral(v)) throw new IllegalArgumentException("'" + v + "' is not a valid IP address");
        return v.toLowerCase(Locale.ROOT); // IPv6 hex
    }

    private static String cidr(String v) {
        int slash = v.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Network must be in CIDR notation, e.g. 192.168.1.0/24");
        String addr = v.substring(0, slash).trim();
        String bits = v.substring(slash + 1).trim();
        if (!IpValidator.isValidLiteral(addr)) throw new IllegalArgumentException("'" + addr + "' is not a valid network address");
        int max = addr.contains(":") ? 128 : 32;
        int prefix;
        try {
            prefix = Integer.parseInt(bits);
        } catch (NumberFormatException e) {
            prefix = -1;
        }
        if (prefix < 0 || prefix > max) throw new IllegalArgumentException("Prefix length must be between 0 and " + max);
        return addr.toLowerCase(Locale.ROOT) + "/" + prefix;
    }

    /** Hostname syntax; allows a leading "*." wildcard and underscores (_dmarc, _sip…). */
    static String domain(String v) {
        String d = v.toLowerCase(Locale.ROOT);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (IpValidator.isValidLiteral(d)) throw new IllegalArgumentException("'" + v + "' is an IP address — use the IP type");
        if (d.contains("://") || d.contains("/")) throw new IllegalArgumentException("Domain must be a bare hostname, without scheme or path");
        String labels = d.startsWith("*.") ? d.substring(2) : d;
        if (labels.isEmpty() || d.length() > 253) throw new IllegalArgumentException("'" + v + "' is not a valid domain");
        for (String label : labels.split("\\.", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) throw new IllegalArgumentException("'" + v + "' is not a valid domain");
        }
        return d;
    }

    private static String email(String v) {
        int at = v.lastIndexOf('@');
        if (at < 1 || at == v.length() - 1) throw new IllegalArgumentException("'" + v + "' is not a valid email address");
        String local = v.substring(0, at);
        if (local.chars().anyMatch(Character::isWhitespace) || local.contains("@")) {
            throw new IllegalArgumentException("'" + v + "' is not a valid email address");
        }
        String dom;
        try {
            dom = domain(v.substring(at + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + v + "' is not a valid email address");
        }
        if (dom.startsWith("*.")) throw new IllegalArgumentException("'" + v + "' is not a valid email address");
        // Whole address lowercased: same convention as the scope classifier's email matching.
        return (local + "@" + dom).toLowerCase(Locale.ROOT);
    }

    /** {@code host:port/proto} — the format AssetImportHelper.resolveOrCreateService produces. */
    private static String service(String v) {
        Matcher m = SERVICE.matcher(v);
        if (!m.matches()) throw new IllegalArgumentException("Service must look like host:port/protocol, e.g. 10.0.0.1:443/tcp");
        String host = m.group(1).trim();
        int port = Integer.parseInt(m.group(2));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        String normHost = IpValidator.isValidLiteral(host) ? host.toLowerCase(Locale.ROOT) : domain(host);
        return normHost + ":" + port + "/" + m.group(3).toLowerCase(Locale.ROOT);
    }

    private static String url(String v) {
        URI uri;
        try {
            uri = new URI(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + v + "' is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("'" + v + "' has no host");
        return v;
    }
}
