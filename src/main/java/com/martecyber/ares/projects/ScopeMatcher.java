package com.martecyber.ares.projects;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.IpCidr;
import com.martecyber.ares.common.IpValidator;

import java.util.*;

/**
 * A set of (kind, value) scope / third-party entries compiled once so each asset is checked with
 * hash lookups instead of a loop over every entry. Semantics are exactly those of
 * {@link #matchesEntry}, the single-entry reference rule (kept for explain views and tests).
 *
 * Immutable and thread-safe once built.
 */
final class ScopeMatcher {

    static final ScopeMatcher EMPTY = new ScopeMatcher();

    // Case-insensitive exact kinds, keyed by asset type.
    private final Map<String, Set<String>> lowerExact = new HashMap<>();
    // Case-sensitive exact kinds (app ids, hardware), keyed by asset type.
    private final Map<String, Set<String>> exact = new HashMap<>();
    // domain_wildcard bases (lowercase): the domain itself or any parent domain matches.
    private final Set<String> wildcardBases = new HashSet<>();
    // url_wildcard lowercase prefixes, and text_contains lowercase needles: few, checked linearly.
    private final List<String> urlPrefixes = new ArrayList<>();
    private final List<String> textNeedles = new ArrayList<>();
    // email_domain values (lowercase).
    private final Set<String> emailDomains = new HashSet<>();
    // ip kind: raw values, plus canonical address keys of the valid ones.
    private final Set<String> ipRaw = new HashSet<>();
    private final Set<String> ipKeys = new HashSet<>();
    // cidr kind: raw values (NETWORK exact), canonical network keys, and parsed blocks for IP containment.
    private final Set<String> cidrRaw = new HashSet<>();
    private final Set<String> cidrKeys = new HashSet<>();
    private final List<Block> cidrBlocks = new ArrayList<>();
    // Entries whose value can't be indexed safely fall back to the reference rule.
    // repository / container_image entries, compared via softwareKey().
    private final Set<String> softwareKeys = new HashSet<>();
    private final List<String[]> fallback = new ArrayList<>();
    private boolean empty = true;

    private record Block(byte[] net, int prefix) {}

    private ScopeMatcher() {}

    static ScopeMatcher compile(Collection<String[]> kindValuePairs) {
        ScopeMatcher m = new ScopeMatcher();
        for (String[] kv : kindValuePairs) m.add(kv[0], kv[1]);
        return m;
    }

    boolean isEmpty() {
        return empty;
    }

    private void add(String kind, String val) {
        if (kind == null || val == null) return;
        empty = false;
        switch (kind) {
            case "domain"      -> lower(AssetType.DOMAIN, val);
            case "url"         -> lower(AssetType.WEB_APPLICATION, val);
            case "email"       -> lower(AssetType.EMAIL, val);
            case "system"      -> lower(AssetType.SYSTEM, val);
            case "directory"   -> lower(AssetType.DIRECTORY, val);
            case "android_app" -> exact.computeIfAbsent(AssetType.ANDROID_APP, k -> new HashSet<>()).add(val);
            case "ios_app"     -> exact.computeIfAbsent(AssetType.IOS_APP, k -> new HashSet<>()).add(val);
            case "windows_app" -> exact.computeIfAbsent(AssetType.WINDOWS_APP, k -> new HashSet<>()).add(val);
            case "hardware"    -> exact.computeIfAbsent(AssetType.HARDWARE, k -> new HashSet<>()).add(val);
            case "domain_wildcard" -> {
                String base = (val.startsWith("*.") ? val.substring(2) : val).toLowerCase();
                // Odd bases ("", ".x", "x.") would make the dot-suffix walk inexact.
                if (base.isEmpty() || base.startsWith(".") || base.endsWith(".")) fallback.add(new String[]{kind, val});
                else wildcardBases.add(base);
            }
            case "url_wildcard"  -> urlPrefixes.add(urlWildcardPrefix(val).toLowerCase());
            case "text_contains" -> textNeedles.add(val.toLowerCase());
            case "repository", "container_image" -> softwareKeys.add(softwareKey(val));
            case "email_domain"  -> emailDomains.add(val.toLowerCase());
            case "ip" -> {
                ipRaw.add(val);
                String key = addressKey(val);
                if (key != null) ipKeys.add(key);
            }
            case "cidr" -> {
                cidrRaw.add(val);
                int prefix = IpCidr.prefixLength(val);
                if (prefix >= 0) {
                    byte[] net = IpCidr.toBytes(val.substring(0, val.indexOf('/')));
                    cidrBlocks.add(new Block(net, prefix));
                    cidrKeys.add(networkKey(net, prefix));
                }
            }
            default -> { /* unknown kinds never match */ }
        }
    }

    private void lower(String assetType, String val) {
        lowerExact.computeIfAbsent(assetType, k -> new HashSet<>()).add(val.toLowerCase());
    }

    boolean matches(Asset asset) {
        if (empty) return false;
        String type = asset.getType();
        String id = asset.getIdentifier();
        if (type == null || id == null) return false;

        Set<String> lowers = lowerExact.get(type);
        if (lowers != null && lowers.contains(id.toLowerCase())) return true;
        Set<String> exacts = exact.get(type);
        if (exacts != null && exacts.contains(id)) return true;

        switch (type) {
            case AssetType.DOMAIN -> {
                if (!wildcardBases.isEmpty()) {
                    String d = id.toLowerCase();
                    if (wildcardBases.contains(d)) return true;
                    for (int i = d.indexOf('.'); i >= 0; i = d.indexOf('.', i + 1)) {
                        if (wildcardBases.contains(d.substring(i + 1))) return true;
                    }
                }
            }
            case AssetType.WEB_APPLICATION -> {
                if (!urlPrefixes.isEmpty()) {
                    String u = id.toLowerCase();
                    for (String p : urlPrefixes) if (u.startsWith(p)) return true;
                }
            }
            case AssetType.SOFTWARE -> {
                if (softwareKeys.contains(softwareKey(id))) return true;
            }
            case AssetType.TEXT_DATA -> {
                if (!textNeedles.isEmpty()) {
                    String t = id.toLowerCase();
                    for (String n : textNeedles) if (t.contains(n)) return true;
                }
            }
            case AssetType.EMAIL -> {
                if (!emailDomains.isEmpty()) {
                    String e = id.toLowerCase();
                    for (int i = e.indexOf('@'); i >= 0; i = e.indexOf('@', i + 1)) {
                        if (emailDomains.contains(e.substring(i + 1))) return true;
                    }
                }
            }
            case AssetType.IP -> {
                if (!ipRaw.isEmpty() || !cidrBlocks.isEmpty()) {
                    String ip = extractIp(id);
                    if (ipRaw.contains(ip)) return true;
                    byte[] addr = IpCidr.toBytes(ip);
                    if (addr != null) {
                        if (ipKeys.contains(hex(addr))) return true;
                        for (Block b : cidrBlocks) if (inBlock(b.net, b.prefix, addr)) return true;
                    }
                }
            }
            case AssetType.NETWORK -> {
                if (cidrRaw.contains(id)) return true;
                if (!cidrKeys.isEmpty()) {
                    int prefix = IpCidr.prefixLength(id);
                    if (prefix >= 0 && cidrKeys.contains(
                            networkKey(IpCidr.toBytes(id.substring(0, id.indexOf('/'))), prefix))) return true;
                }
            }
            default -> { }
        }

        for (String[] kv : fallback) if (matchesEntry(asset, kv[0], kv[1])) return true;
        return false;
    }

    // ── Reference single-entry rule ──────────────────────────────────────────

    static boolean matchesEntry(Asset asset, String kind, String val) {
        String id = asset.getIdentifier();
        if (kind == null || val == null || id == null) return false;
        return switch (kind) {
            case "domain"          -> AssetType.DOMAIN.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "domain_wildcard" -> AssetType.DOMAIN.equals(asset.getType())
                                      && matchesWildcardDomain(id, val);
            case "url"             -> AssetType.WEB_APPLICATION.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "url_wildcard"    -> AssetType.WEB_APPLICATION.equals(asset.getType())
                                      && id.toLowerCase().startsWith(urlWildcardPrefix(val).toLowerCase());
            case "ip"              -> AssetType.IP.equals(asset.getType())
                                      && sameIp(extractIp(id), val);
            case "cidr"            -> (AssetType.IP.equals(asset.getType())
                                      && IpCidr.contains(val, extractIp(id)))
                                      || (AssetType.NETWORK.equals(asset.getType())
                                      && (id.equals(val) || IpCidr.sameNetwork(id, val)));
            case "android_app"     -> AssetType.ANDROID_APP.equals(asset.getType())  && id.equals(val);
            case "ios_app"         -> AssetType.IOS_APP.equals(asset.getType())      && id.equals(val);
            case "windows_app"     -> AssetType.WINDOWS_APP.equals(asset.getType())  && id.equals(val);
            case "hardware"        -> AssetType.HARDWARE.equals(asset.getType())     && id.equals(val);
            case "text_contains"   -> AssetType.TEXT_DATA.equals(asset.getType())
                                      && id.toLowerCase().contains(val.toLowerCase());
            case "email"           -> AssetType.EMAIL.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "email_domain"    -> AssetType.EMAIL.equals(asset.getType())
                                      && id.toLowerCase().endsWith("@" + val.toLowerCase());
            case "system"          -> AssetType.SYSTEM.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "directory"       -> AssetType.DIRECTORY.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "repository", "container_image" -> AssetType.SOFTWARE.equals(asset.getType())
                                      && softwareKey(id).equals(softwareKey(val));
            default                -> false;
        };
    }

    /** A git repository URL or container image reference, compared case-insensitively and
     *  ignoring a trailing "/" or ".git" (https://github.com/acme/app.git == .../acme/app). */
    static String softwareKey(String v) {
        String k = v.trim().toLowerCase();
        while (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        if (k.endsWith(".git")) k = k.substring(0, k.length() - 4);
        return k;
    }

    /** *.example.com matches api.example.com and example.com itself. */
    private static boolean matchesWildcardDomain(String domain, String wildcard) {
        String base = wildcard.startsWith("*.") ? wildcard.substring(2) : wildcard;
        return domain.equalsIgnoreCase(base)
            || domain.toLowerCase().endsWith("." + base.toLowerCase());
    }

    /** https://example.com/api/* → https://example.com/api (the part before the wildcard). */
    private static String urlWildcardPrefix(String wildcardUrl) {
        return wildcardUrl.replaceAll("/\\*.*$", "").replaceAll("\\*$", "").stripTrailing();
    }

    /** Bare IP of an IP asset identifier: IPv4/IPv6 literal as-is, legacy "ip:port/proto" stripped. */
    static String extractIp(String id) {
        if (id == null) return "";
        if (IpValidator.isValidLiteral(id.trim())) return id.trim();
        return id.contains(":") ? id.split(":")[0] : id;
    }

    /** Exact match, or the same address written differently (IPv6 zero-compression/case). */
    private static boolean sameIp(String a, String b) {
        return a.equals(b) || IpCidr.sameAddress(a, b);
    }

    // ── Canonical keys ───────────────────────────────────────────────────────

    private static String addressKey(String ip) {
        byte[] b = IpCidr.toBytes(ip);
        return b == null ? null : hex(b);
    }

    /** Family + prefix + masked network bytes: equal keys ⇔ {@link IpCidr#sameNetwork}. */
    private static String networkKey(byte[] net, int prefix) {
        byte[] masked = net.clone();
        for (int i = 0; i < masked.length; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            masked[i] &= (byte) ((0xFF << (8 - bits)) & 0xFF);
        }
        return hex(masked) + "/" + prefix;
    }

    private static boolean inBlock(byte[] net, int prefix, byte[] addr) {
        if (addr.length != net.length) return false;
        int full = prefix / 8;
        for (int i = 0; i < full; i++) if (net[i] != addr[i]) return false;
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (net[full] & mask) == (addr[full] & mask);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
