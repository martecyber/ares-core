package com.martecyber.ares.kb.thirdparty;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.JobDto;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KbThirdPartyEntryService {

    private final KbThirdPartyEntryRepository repo;
    private final ScopeClassifyScheduler scheduler;
    private final JobService jobService;

    public KbThirdPartyEntryService(KbThirdPartyEntryRepository repo, ScopeClassifyScheduler scheduler,
                                     JobService jobService) {
        this.repo = repo;
        this.scheduler = scheduler;
        this.jobService = jobService;
    }

    public Page<KbThirdPartyEntry> list(String category, String kind, Boolean enabled, String q, Pageable pageable) {
        return repo.filter(category, kind, enabled, q, pageable);
    }

    public List<KbThirdPartyEntry> listEnabled() {
        return repo.findAllByEnabledTrue();
    }

    public KbThirdPartyEntry create(KbThirdPartyEntry entry) {
        KbThirdPartyEntry saved = repo.save(entry);
        // A brand-new pattern is a platform-wide change — reclassify every project's
        // already-indeterminate assets against it, not just whatever project someone
        // happens to touch next.
        if (saved.isEnabled()) scheduler.scheduleThirdPartyReclassify();
        return saved;
    }

    public KbThirdPartyEntry update(Long id, KbThirdPartyEntry patch) {
        KbThirdPartyEntry entry = repo.findById(id).orElseThrow(() -> NotFoundException.of("kb_third_party_entry", id));
        entry.setKind(patch.getKind());
        entry.setValue(patch.getValue());
        entry.setCategory(patch.getCategory());
        entry.setNotes(patch.getNotes());
        entry.setEnabled(patch.isEnabled());
        KbThirdPartyEntry saved = repo.save(entry);
        // kind/value may have changed to newly match assets that didn't match before.
        if (saved.isEnabled()) scheduler.scheduleThirdPartyReclassify();
        return saved;
    }

    public void toggle(Long id) {
        KbThirdPartyEntry entry = repo.findById(id).orElseThrow(() -> NotFoundException.of("kb_third_party_entry", id));
        entry.setEnabled(!entry.isEnabled());
        repo.save(entry);
        // Only re-enabling matters here — disabling doesn't create new matches, and
        // existing THIRD_PARTY assets are never automatically reverted (see classify() step 5).
        if (entry.isEnabled()) scheduler.scheduleThirdPartyReclassify();
    }

    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("kb_third_party_entry", id);
        repo.deleteById(id);
    }

    /** Manual "force reclassify" trigger — creates a trackable Job and runs it immediately. */
    public Long reclassifyAsJob() {
        JobDto job = jobService.create(new CreateJobRequest("KB_RECLASSIFY_THIRD_PARTY", null, null, null));
        scheduler.runThirdPartyReclassifyJobAsync(job.id());
        return job.id();
    }
}
