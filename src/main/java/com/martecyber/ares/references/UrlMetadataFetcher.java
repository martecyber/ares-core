package com.martecyber.ares.references;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort fetcher for a URL reference's page {@code <title>} and favicon.
 *
 * The URLs handled here are attacker-influenceable — either typed directly by a user
 * into the "add URL reference" dialog, or lifted from scanner output during import — so
 * every fetch is guarded against SSRF: only http/https, only publicly-routable
 * destinations (each redirect hop is re-resolved and re-checked, not just the first
 * URL), bounded response size, short timeouts. Nothing here ever throws out to the
 * caller — failures just mean "no metadata", not a broken import/dialog.
 */
@Component
public class UrlMetadataFetcher {

    private static final Logger log = LoggerFactory.getLogger(UrlMetadataFetcher.class);

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;   // 2 MB
    private static final int MAX_FAVICON_BYTES = 512 * 1024;     // 512 KB
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final String USER_AGENT = "AresBot/1.0 (+reference-metadata-fetcher)";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public record Metadata(String title, byte[] faviconBytes, String faviconContentType) {
        static final Metadata EMPTY = new Metadata(null, null, null);
    }

    private record FetchResult(int status, byte[] body, String contentType, URI finalUri) {}

    public Metadata fetch(String rawUrl) {
        try {
            URI start = validate(rawUrl);
            FetchResult page = getFollowingRedirects(start, MAX_HTML_BYTES);
            if (page == null || page.status() >= 400) return Metadata.EMPTY;

            String html = new String(page.body(), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, page.finalUri().toString());

            String title = doc.title();
            if (title != null) title = title.strip();
            if (title == null || title.isEmpty()) title = null;

            byte[] faviconBytes = null;
            String faviconContentType = null;
            List<String> candidates = new ArrayList<>();
            String declaredHref = doc.select("link[rel~=(?i)^(shortcut icon|icon|apple-touch-icon)$]").attr("abs:href");
            if (declaredHref != null && !declaredHref.isBlank()) candidates.add(declaredHref);
            candidates.add(page.finalUri().resolve("/favicon.ico").toString());

            for (String candidate : candidates) {
                try {
                    URI favUri = validate(candidate);
                    FetchResult fav = getFollowingRedirects(favUri, MAX_FAVICON_BYTES);
                    if (fav == null || fav.status() >= 300 || fav.body().length == 0) continue;
                    String ct = fav.contentType();
                    String baseType = ct != null ? (ct.contains(";") ? ct.substring(0, ct.indexOf(';')).trim() : ct) : null;
                    // Blocklist rather than allowlist: reject only the one clear sign this
                    // isn't actually a favicon (an HTML error/placeholder page served with a
                    // 200). Plenty of real sites serve favicons with a missing, generic
                    // (application/octet-stream) or otherwise "wrong" content-type header —
                    // requiring image/* here silently dropped those.
                    boolean looksLikeHtml = baseType != null && baseType.toLowerCase(java.util.Locale.ROOT).startsWith("text/html");
                    if (!looksLikeHtml) {
                        faviconBytes = fav.body();
                        faviconContentType = (baseType != null && !baseType.isBlank()) ? baseType : "image/x-icon";
                        break;
                    }
                } catch (Exception ignored) {
                    // try the next favicon candidate
                }
            }

            return new Metadata(title, faviconBytes, faviconContentType);
        } catch (Exception e) {
            log.debug("URL metadata fetch skipped for '{}': {}", rawUrl, e.toString());
            return Metadata.EMPTY;
        }
    }

    // ── Fetch with bounded, per-hop-revalidated redirects ───────────────────────

    private FetchResult getFollowingRedirects(URI uri, int maxBytes) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            assertPubliclyRoutable(current);
            HttpRequest req = HttpRequest.newBuilder(current)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String location = resp.headers().firstValue("Location").orElse(null);
                resp.body().close();
                if (location == null) return null;
                current = current.resolve(location);
                continue;
            }
            byte[] body;
            try (InputStream in = resp.body()) {
                body = readBounded(in, maxBytes);
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse(null);
            return new FetchResult(status, body, contentType, resp.uri());
        }
        return null; // too many redirects
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while (total < maxBytes && (n = in.read(buf)) != -1) {
            int toWrite = Math.min(n, maxBytes - total);
            out.write(buf, 0, toWrite);
            total += n;
        }
        return out.toByteArray();
    }

    // ── URL validation / SSRF guard ──────────────────────────────────────────────

    /** Throws if the URL is malformed, not http/https, or resolves to a non-public address. */
    public static URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("URL is required");
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URL: " + rawUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL must include a host");
        }
        assertPubliclyRoutable(uri);
        return uri;
    }

    private static void assertPubliclyRoutable(URI uri) {
        String host = uri.getHost();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve host: " + host, e);
        }
        if (addresses.length == 0) throw new IllegalArgumentException("Cannot resolve host: " + host);
        for (InetAddress addr : addresses) {
            if (!isPubliclyRoutable(addr)) {
                throw new IllegalArgumentException("Refusing to fetch non-public address: " + addr.getHostAddress());
            }
        }
    }

    private static boolean isPubliclyRoutable(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return false;
        }
        byte[] b = addr.getAddress();
        // IPv6 unique local addresses (fc00::/7) — not covered by isSiteLocalAddress().
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return false;
        return true;
    }
}
