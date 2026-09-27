package com.martecyber.ares.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.plugins.dto.RepositoryCatalogDto;
import com.martecyber.ares.plugins.dto.RepositoryPluginVersionsDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads a plugin repository's static, directory-based index over plain HTTPS — see {@link
 * PluginBrowseService}'s own doc comment for the {@code /index.json} + {@code
 * /plugins/&lt;id&gt;/index.json} layout this expects. Never writes to a repository; publishing
 * is entirely the concern of each plugin's own CI (see the {@code publish-plugin.yml} workflow
 * template). Used by {@link PluginRepositorySourceService} (validating a repo on add), {@link
 * PluginBrowseService} (the browse/install-source-of-truth), and {@link PluginService}
 * (resolving a specific version's {@code downloadUrl}/checksum at install time).
 */
@Component
class PluginRepositoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Fetches {@code <baseUrl>/index.json}. */
    RepositoryCatalogDto fetchCatalog(String baseUrl) {
        return fetchJson(resolve(baseUrl, "index.json"), RepositoryCatalogDto.class);
    }

    /** Fetches a plugin's own version-history file — {@code path} is the catalog entry's own
     *  {@code path} field when known (repos are free to lay it out differently), falling back to
     *  the {@code plugins/<id>/index.json} convention when the catalog didn't say. */
    RepositoryPluginVersionsDto fetchPluginVersions(String baseUrl, String pluginId, String path) {
        String resolvedPath = (path != null && !path.isBlank()) ? path : "plugins/" + pluginId + "/index.json";
        return fetchJson(resolve(baseUrl, resolvedPath), RepositoryPluginVersionsDto.class);
    }

    /** Resolves a version entry's own {@code downloadUrl} — absolute URLs pass through, relative
     *  ones resolve against the repository's base URL. */
    String resolveDownloadUrl(String baseUrl, String downloadUrl) {
        return resolve(baseUrl, downloadUrl);
    }

    private <T> T fetchJson(String url, Class<T> type) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Repository returned HTTP " + resp.statusCode() + " for " + url);
            }
            return MAPPER.readValue(resp.body(), type);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not fetch " + url + ": " + e.getMessage());
        }
    }

    private static String resolve(String baseUrl, String relativeOrAbsolute) {
        if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            return relativeOrAbsolute;
        }
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String rel = relativeOrAbsolute.startsWith("/") ? relativeOrAbsolute.substring(1) : relativeOrAbsolute;
        return base + rel;
    }
}
