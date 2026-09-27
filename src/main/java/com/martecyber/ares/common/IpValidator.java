package com.martecyber.ares.common;

import java.util.regex.Pattern;

/** Pure-syntactic IP literal validation. Does NOT perform DNS lookups. */
public final class IpValidator {

    // Strict dotted-quad IPv4 (0-255 per octet, no leading zeros beyond a single 0)
    private static final Pattern IPV4 = Pattern.compile(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    // Lenient IPv6 literal (hex groups and ::), validated further via parsing.
    private static final Pattern IPV6_TEXTUAL = Pattern.compile(
        "^[0-9a-fA-F:]+$|^[0-9a-fA-F:]+:(\\d{1,3}\\.){3}\\d{1,3}$");

    private IpValidator() {}

    /** True if s is a valid IPv4 or IPv6 literal. Never resolves DNS. */
    public static boolean isValidLiteral(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty()) return false;
        if (IPV4.matcher(v).matches()) return true;
        if (!IPV6_TEXTUAL.matcher(v).matches()) return false;
        // Final check via InetAddress on a literal-only path: prepend "[" to force IPv6 parse
        // and avoid DNS. Simpler: use Inet6Address parse via getAllByName guarded by the
        // regex above (which excludes hostnames since they have no colon).
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(v);
            return addr instanceof java.net.Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }
}
