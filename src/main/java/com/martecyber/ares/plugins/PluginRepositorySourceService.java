package com.martecyber.ares.plugins;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.plugins.dto.PluginRepositorySourceDto;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CRUD for registered plugin repositories (see {@link PluginRepositorySource}'s own doc comment).
 * One official row is seeded by migration V202; official rows can be disabled but never deleted,
 * enforced here.
 */
@Service
public class PluginRepositorySourceService {

    private final PluginRepositorySourceRepository repo;
    private final PluginRepositoryClient client;

    public PluginRepositorySourceService(PluginRepositorySourceRepository repo, PluginRepositoryClient client) {
        this.repo = repo;
        this.client = client;
    }

    public List<PluginRepositorySourceDto> list() {
        return repo.findAllByOrderByAddedAtAsc().stream().map(PluginRepositorySourceDto::from).toList();
    }

    /** Validates the URL by actually fetching its {@code index.json} before saving — a typo'd or
     *  unreachable repository is rejected immediately instead of silently sitting in the list
     *  until the next browse. */
    public PluginRepositorySourceDto add(String name, String baseUrl, Long addedBy) {
        var catalog = client.fetchCatalog(baseUrl); // throws ResponseStatusException(BAD_GATEWAY) on failure
        PluginRepositorySource s = new PluginRepositorySource();
        s.setName((name != null && !name.isBlank()) ? name : catalog.repositoryName());
        s.setBaseUrl(baseUrl);
        s.setOfficial(false);
        s.setEnabled(true);
        s.setAddedBy(addedBy);
        s.setAddedAt(OffsetDateTime.now());
        return PluginRepositorySourceDto.from(repo.save(s));
    }

    public PluginRepositorySourceDto setEnabled(Long id, boolean enabled) {
        PluginRepositorySource s = find(id);
        s.setEnabled(enabled);
        return PluginRepositorySourceDto.from(repo.save(s));
    }

    public void delete(Long id) {
        PluginRepositorySource s = find(id);
        if (s.isOfficial()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The official repository can be disabled but not removed");
        }
        repo.delete(s);
    }

    private PluginRepositorySource find(Long id) {
        return repo.findById(id).orElseThrow(() -> NotFoundException.of("plugin repository", id));
    }
}
