package com.martecyber.ares.kb.wordlists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GithubRepoSyncService {

    private static final Logger log = LoggerFactory.getLogger(GithubRepoSyncService.class);
    private static final Pattern GITHUB_URL = Pattern.compile(
        "https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$"
    );
    private static final int PARALLEL_DOWNLOADS  = 8;
    private static final int BATCH_SIZE          = 100;
    // Files larger than this are skipped to avoid OOM (rockyou.txt = 133 MB, xato = 100 MB, etc.)
    private static final long MAX_FILE_BYTES     = 20 * 1024 * 1024; // 20 MB

    private final KbWordlistRepoRepository repoRepo;
    private final KbWordlistRepository wordlistRepo;
    private final KbWordlistFolderRepository folderRepo;
    private final S3Client s3;
    private final ObjectMapper mapper;
    private final JobService jobService;
    private final Set<Long> syncing = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService downloadPool = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${ares.storage.s3.buckets.wordlists}")
    private String bucket;

    // Self-injection so that @Async and @Transactional on sync() go through the Spring proxy
    @Autowired @Lazy
    private GithubRepoSyncService self;

    public GithubRepoSyncService(KbWordlistRepoRepository repoRepo,
                                  KbWordlistRepository wordlistRepo,
                                  KbWordlistFolderRepository folderRepo,
                                  S3Client s3,
                                  ObjectMapper mapper,
                                  JobService jobService) {
        this.repoRepo = repoRepo;
        this.wordlistRepo = wordlistRepo;
        this.folderRepo = folderRepo;
        this.s3 = s3;
        this.mapper = mapper;
        this.jobService = jobService;
    }

    // ── auto-sync scheduler ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void runDue() {
        OffsetDateTime cutoff = OffsetDateTime.now();
        List<KbWordlistRepo> due = repoRepo.findDueForSync(cutoff);
        for (KbWordlistRepo repo : due) {
            OffsetDateTime lastSync = repo.getLastSyncAt();
            if (lastSync != null) {
                long hoursSince = Duration.between(lastSync, OffsetDateTime.now()).toHours();
                if (hoursSince < repo.getSyncIntervalHours()) continue;
            }
            try { syncAsync(repo.getId()); }
            catch (Exception e) { log.error("Auto-sync error repo={}: {}", repo.getId(), e.getMessage()); }
        }
    }

    /** Creates the job and kicks off the async sync. Returns the job ID immediately. */
    public Long syncAsync(Long repoId) {
        KbWordlistRepo repo = repoRepo.findById(repoId)
            .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + repoId));

        String payload = "{\"repoId\":" + repoId + ",\"repoUrl\":\"" + repo.getRepoUrl() + "\"}";
        var job = jobService.create(new CreateJobRequest("KB_SYNC_WORDLIST_REPO", null, payload, null));
        Long jobId = job.id();

        // Called via Spring proxy so @Async + @Transactional are honoured
        self.syncInBackground(repoId, jobId);

        return jobId;
    }

    @Async
    public void syncInBackground(Long repoId, Long jobId) {
        if (!syncing.add(repoId)) {
            log.info("Sync already running for repo={}", repoId);
            safeSetJobFailed(jobId, "Sync already in progress for this repository");
            return;
        }
        KbWordlistRepo repo = repoRepo.findById(repoId)
            .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + repoId));
        repo.setLastSyncStatus("running");
        repoRepo.save(repo);
        safeUpdateJob(jobId, "running", 0, "Starting sync…");

        try {
            doSync(repo, jobId);
            repo.setLastSyncStatus("success");
            repo.setLastSyncError(null);
            safeUpdateJob(jobId, "completed", 100, "Sync completed: " + repo.getFileCount() + " files");
        } catch (Exception e) {
            log.error("Sync failed repo={}: {}", repoId, e.getMessage(), e);
            repo.setLastSyncStatus("failed");
            repo.setLastSyncError(e.getMessage());
            safeSetJobFailed(jobId, e.getMessage());
        } finally {
            repo.setLastSyncAt(OffsetDateTime.now());
            repoRepo.save(repo);
            syncing.remove(repoId);
        }
    }

    private void doSync(KbWordlistRepo repo, Long jobId) throws Exception {
        Matcher m = GITHUB_URL.matcher(repo.getRepoUrl().trim());
        if (!m.matches()) throw new IllegalArgumentException("Not a valid GitHub URL: " + repo.getRepoUrl());
        String owner = m.group(1);
        String repoName = m.group(2);
        String configuredBranch = repo.getBranch() != null && !repo.getBranch().isBlank()
            ? repo.getBranch() : "main";

        safeUpdateJob(jobId, "running", 2, "Resolving branch…");
        String branch = resolveActualBranch(owner, repoName, configuredBranch, repo.getGithubToken());

        safeUpdateJob(jobId, "running", 5, "Fetching repository tree…");
        List<TreeEntry> entries = fetchTree(owner, repoName, branch, repo.getGithubToken());

        List<TreeEntry> matching = filterEntries(entries, repo.getPathFilter(), repo.isImportAllTypes());
        log.info("Repo {} tree: {} total blobs, {} match filter", repo.getRepoUrl(), entries.size(), matching.size());

        if (matching.isEmpty()) {
            repo.setFileCount(0);
            safeUpdateJob(jobId, "completed", 100, "No files matched the filter (0 files)");
            return;
        }

        // Filter out oversized files to prevent OOM (GitHub tree API provides sizes)
        List<TreeEntry> toDownload = new ArrayList<>();
        int skippedLarge = 0;
        for (TreeEntry e : matching) {
            if (e.size() > MAX_FILE_BYTES) {
                log.info("Skipping {} ({} MB) — exceeds {}MB limit",
                    e.path(), e.size() / (1024 * 1024), MAX_FILE_BYTES / (1024 * 1024));
                skippedLarge++;
            } else {
                toDownload.add(e);
            }
        }
        if (skippedLarge > 0) {
            log.warn("Skipped {} file(s) exceeding {}MB size limit", skippedLarge, MAX_FILE_BYTES / (1024 * 1024));
        }

        safeUpdateJob(jobId, "running", 8, "Building folder structure…");
        Long rootFolderId = ensureRepoFolder(repo.getId(), repoName);
        Map<String, Long> folderTree = buildFolderTree(rootFolderId, toDownload);

        int total = toDownload.size();
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        Set<String> processedPaths = ConcurrentHashMap.newKeySet();
        Semaphore semaphore = new Semaphore(PARALLEL_DOWNLOADS);

        safeUpdateJob(jobId, "running", 10,
            "Downloading " + total + " files in batches of " + BATCH_SIZE + "…");

        // Process in batches to bound the number of live futures (prevents OOM
        // from thousands of byte[] arrays in memory simultaneously)
        for (int batchStart = 0; batchStart < total; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, total);
            List<TreeEntry> batch = toDownload.subList(batchStart, batchEnd);
            List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());

            for (TreeEntry entry : batch) {
                futures.add(CompletableFuture.runAsync(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        upsertWordlist(repo.getId(), folderTree, owner, repoName, branch, entry, repo.getGithubToken());
                        processedPaths.add(entry.path());
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        log.warn("Failed to sync {} from {}: {}", entry.path(), repo.getRepoUrl(), e.getMessage());
                    } finally {
                        semaphore.release();
                        int n = done.incrementAndGet();
                        if (n % 50 == 0 || n == total) {
                            int pct = 10 + (int)(n * 80L / total);
                            safeUpdateJob(jobId, "running", pct,
                                "Downloaded " + n + "/" + total +
                                (errors.get() > 0 ? " (" + errors.get() + " errors)" : ""));
                        }
                    }
                }, downloadPool));
            }
            // Wait for this batch to complete before spawning the next
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        safeUpdateJob(jobId, "running", 92, "Cleaning up removed files…");
        if (!processedPaths.isEmpty()) {
            wordlistRepo.deleteBySourceRepoIdAndSourceRepoPathNotIn(repo.getId(), processedPaths);
        }

        repo.setFileCount(processedPaths.size());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String resolveActualBranch(String owner, String repoName, String configuredBranch, String token) {
        try {
            String infoUrl = String.format("https://api.github.com/repos/%s/%s", owner, repoName);
            JsonNode info = githubGet(infoUrl, token);
            String defaultBranch = info.path("default_branch").asText(null);
            if (defaultBranch != null && !defaultBranch.isBlank() && !defaultBranch.equals(configuredBranch)) {
                log.info("Branch '{}' differs from default '{}' for {}/{}; using default",
                    configuredBranch, defaultBranch, owner, repoName);
                return defaultBranch;
            }
        } catch (Exception e) {
            log.warn("Could not fetch repo metadata for {}/{}: {}", owner, repoName, e.getMessage());
        }
        return configuredBranch;
    }

    private record TreeEntry(String path, String sha, long size) {}

    private List<TreeEntry> fetchTree(String owner, String repo, String branch,
                                       String token) throws Exception {
        String url = String.format(
            "https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1", owner, repo, branch);
        JsonNode root = githubGet(url, token);
        List<TreeEntry> entries = new ArrayList<>();
        JsonNode tree = root.get("tree");
        if (tree == null || !tree.isArray()) return entries;
        for (JsonNode node : tree) {
            if (!"blob".equals(node.path("type").asText())) continue;
            entries.add(new TreeEntry(
                node.path("path").asText(),
                node.path("sha").asText(),
                node.path("size").asLong(0)
            ));
        }
        return entries;
    }

    private static boolean isWordlistFile(String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase();
        if (!name.contains(".")) return true; // no extension — likely a wordlist
        return name.endsWith(".txt") || name.endsWith(".lst") || name.endsWith(".dict");
    }

    private List<TreeEntry> filterEntries(List<TreeEntry> entries, String pathFilter, boolean importAllTypes) {
        if (pathFilter == null || pathFilter.isBlank()) {
            if (importAllTypes) return entries;
            return entries.stream().filter(e -> isWordlistFile(e.path())).toList();
        }
        String pattern = pathFilter.trim();
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) pattern = "glob:" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(pattern);
        List<TreeEntry> result = new ArrayList<>();
        for (TreeEntry e : entries) {
            if (matcher.matches(Path.of(e.path()))) result.add(e);
        }
        return result;
    }

    /** Finds or creates the root folder for a repo. */
    private Long ensureRepoFolder(Long repoId, String repoName) {
        List<KbWordlist> existing = wordlistRepo.findBySourceRepoId(repoId);
        // Try to find the root folder by walking up from any known wordlist
        for (KbWordlist wl : existing) {
            if (wl.getFolderId() != null) {
                // Walk up to find the root-level folder for this repo
                KbWordlistFolder f = folderRepo.findById(wl.getFolderId()).orElse(null);
                while (f != null && f.getParentId() != null) {
                    f = folderRepo.findById(f.getParentId()).orElse(null);
                }
                if (f != null) return f.getId();
            }
        }
        KbWordlistFolder folder = new KbWordlistFolder();
        folder.setName(repoName);
        folder.setCreatedAt(OffsetDateTime.now());
        return folderRepo.save(folder).getId();
    }

    /**
     * Builds a map of dirPath → folderId for all directories implied by the entries.
     * Uses getOrCreateFolder to guarantee correct parent-child structure, both on
     * initial sync and on re-sync (where stale folders from a previous run may exist).
     * "" maps to rootFolderId (the repo root folder itself).
     */
    private Map<String, Long> buildFolderTree(Long rootFolderId, List<TreeEntry> entries) {
        Map<String, Long> tree = new HashMap<>();
        tree.put("", rootFolderId);

        // Collect all unique directory paths, sorted parent-first
        Set<String> allDirs = new java.util.LinkedHashSet<>();
        for (TreeEntry entry : entries) {
            String path = entry.path();
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                String dir = path.substring(0, slash);
                String[] parts = dir.split("/");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(part);
                    allDirs.add(sb.toString());
                }
            }
        }
        List<String> neededDirs = new ArrayList<>(allDirs);
        neededDirs.sort(Comparator.comparingInt((String s) -> s.split("/", -1).length)
                                  .thenComparing(Comparator.naturalOrder()));

        // Ensure each folder exists under the correct parent (idempotent)
        for (String dirPath : neededDirs) {
            int lastSlash = dirPath.lastIndexOf('/');
            String parentPath = lastSlash > 0 ? dirPath.substring(0, lastSlash) : "";
            String name = lastSlash > 0 ? dirPath.substring(lastSlash + 1) : dirPath;
            Long parentId = tree.get(parentPath);
            if (parentId == null) {
                log.error("BUG: parent '{}' not in tree when processing '{}'", parentPath, dirPath);
                continue;
            }
            tree.put(dirPath, getOrCreateFolder(parentId, name));
        }
        return tree;
    }

    /**
     * Returns the ID of an existing folder with the given name under parentId,
     * or creates a new one. Idempotent — safe to call on every sync.
     */
    private Long getOrCreateFolder(Long parentId, String name) {
        return folderRepo.findByParentId(parentId).stream()
            .filter(f -> name.equals(f.getName()))
            .findFirst()
            .map(KbWordlistFolder::getId)
            .orElseGet(() -> {
                KbWordlistFolder folder = new KbWordlistFolder();
                folder.setName(name);
                folder.setParentId(parentId);
                folder.setCreatedAt(OffsetDateTime.now());
                return folderRepo.save(folder).getId();
            });
    }

    @Transactional
    protected void upsertWordlist(Long repoId, Map<String, Long> folderTree, String owner, String repoName,
                                   String branch, TreeEntry entry, String token) throws Exception {
        Optional<KbWordlist> existing = wordlistRepo.findBySourceRepoIdAndSourceRepoPath(repoId, entry.path());
        if (existing.isPresent() && entry.sha().equals(existing.get().getSourceRepoSha1())) return;

        String rawUrl = "https://raw.githubusercontent.com/" + owner + "/" + repoName
            + "/" + branch + "/" + encodePathSegments(entry.path());
        byte[] content = rawDownload(rawUrl, token);

        String sha256 = sha256Hex(content);
        long lineCount = countLines(content);
        String fileName = Path.of(entry.path()).getFileName().toString();

        // Resolve the correct subfolder from the folder tree
        String entryPath = entry.path();
        int lastSlash = entryPath.lastIndexOf('/');
        String dirPath = lastSlash > 0 ? entryPath.substring(0, lastSlash) : "";
        Long folderId = folderTree.getOrDefault(dirPath, folderTree.get(""));

        // Generate key before saving to avoid NOT NULL constraint violation on first save
        boolean isNew = existing.isEmpty();
        String objectKey = isNew
            ? "wordlists/" + java.util.UUID.randomUUID() + "/" + fileName
            : existing.get().getObjectKey();

        KbWordlist wl = existing.orElseGet(KbWordlist::new);
        wl.setFolderId(folderId);
        wl.setName(fileName);  // just the filename, not full path
        wl.setObjectKey(objectKey);
        wl.setSizeBytes((long) content.length);
        wl.setSha256(sha256);
        wl.setLineCount(lineCount);
        wl.setSourceRepoId(repoId);
        wl.setSourceRepoPath(entry.path());
        wl.setSourceRepoSha1(entry.sha());
        wl.setUpdatedAt(OffsetDateTime.now());
        if (isNew) wl.setCreatedAt(OffsetDateTime.now());

        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType("text/plain").build(),
            RequestBody.fromBytes(content)
        );
        wordlistRepo.save(wl);
    }

    /** URL-encodes each path segment individually, preserving '/' separators. */
    private static String encodePathSegments(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            try {
                // URLEncoder converts spaces to '+'; we need '%20'. Also encode '+' itself as '%2B'.
                sb.append(java.net.URLEncoder.encode(segments[i], java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%2B", "%2B")); // '+' in filename stays as %2B (already correct)
            } catch (Exception e) {
                sb.append(segments[i]); // fallback: use raw segment
            }
        }
        return sb.toString();
    }

    private JsonNode githubGet(String url, String token) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").GET();
        if (token != null && !token.isBlank()) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409) return mapper.createObjectNode().set("tree", mapper.createArrayNode());
        if (resp.statusCode() >= 400) {
            String body = resp.body();
            throw new RuntimeException("GitHub API error " + resp.statusCode() + ": " +
                body.substring(0, Math.min(200, body.length())));
        }
        return mapper.readTree(resp.body());
    }

    private byte[] rawDownload(String url, String token) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(url)).timeout(Duration.ofSeconds(120)).GET();
        if (token != null && !token.isBlank()) req.header("Authorization", "Bearer " + token);
        HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) throw new RuntimeException("Download error " + resp.statusCode() + " for " + url);
        return resp.body();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static long countLines(byte[] data) {
        long count = 0;
        for (byte b : data) if (b == '\n') count++;
        return count;
    }

    private void safeUpdateJob(Long jobId, String status, int pct, String detail) {
        try { jobService.update(jobId, new UpdateJobRequest(status, pct, toJsonString(detail), null)); }
        catch (Exception e) { log.warn("Could not update job {}: {}", jobId, e.getMessage()); }
    }

    private void safeSetJobFailed(Long jobId, String error) {
        try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, toJsonString(error))); }
        catch (Exception e) { log.warn("Could not fail job {}: {}", jobId, e.getMessage()); }
    }

    /** Wraps a plain string in JSON double-quotes so it is valid for jsonb columns. */
    private static String toJsonString(String s) {
        if (s == null) return null;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
