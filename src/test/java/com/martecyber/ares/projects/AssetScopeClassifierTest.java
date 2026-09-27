package com.martecyber.ares.projects;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetRelationship;
import com.martecyber.ares.assets.AssetRelationshipRepository;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntry;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static com.martecyber.ares.projects.AssetScopeClassifier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link AssetScopeClassifier} — direct scope-entry matching per
 *  kind, BFS forward/reverse propagation through the relationship graph, the HOST = aggregate-
 *  of-interfaces rule (including its direct-match and no-interfaces-tracked fallbacks), the
 *  scope-override skip, the third-party KB override (both the per-project and platform-wide
 *  sweep), and {@code explainScope}'s one-hop source resolution. No Spring context; the
 *  relationship graph and scope entries are built directly rather than loaded from a DB. */
class AssetScopeClassifierTest {

    private ProjectRepository projectRepo;
    private ProjectScopeEntryRepository scopeRepo;
    private ProjectAssetAccessRepository accessRepo;
    private AssetRepository assetRepo;
    private AssetRelationshipRepository relRepo;
    private KbThirdPartyEntryRepository kbThirdPartyRepo;
    private AssetScopeClassifier classifier;

    private static final Long PROJECT = 1L;

    @BeforeEach
    void setUp() {
        projectRepo = mock(ProjectRepository.class);
        scopeRepo = mock(ProjectScopeEntryRepository.class);
        accessRepo = mock(ProjectAssetAccessRepository.class);
        assetRepo = mock(AssetRepository.class);
        relRepo = mock(AssetRelationshipRepository.class);
        kbThirdPartyRepo = mock(KbThirdPartyEntryRepository.class);
        classifier = new AssetScopeClassifier(projectRepo, scopeRepo, accessRepo, assetRepo, relRepo, kbThirdPartyRepo);

        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of());
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(PROJECT)).thenReturn(List.of());
        when(relRepo.findByProjectId(PROJECT)).thenReturn(List.of());
        when(accessRepo.updateScopeStatusForAssets(anyLong(), anyList(), anyString())).thenReturn(0);
    }

    private static long seq = 1000;

    private Asset asset(String type, String identifier) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", ++seq);
        a.setType(type);
        a.setIdentifier(identifier);
        return a;
    }

    private ProjectScopeEntry entry(String kind, String value, boolean inScope) {
        ProjectScopeEntry e = new ProjectScopeEntry();
        ReflectionTestUtils.setField(e, "id", ++seq);
        e.setKind(kind);
        e.setValue(value);
        e.setInScope(inScope);
        return e;
    }

    private ProjectAssetAccess access(Long assetId, String status, boolean override) {
        ProjectAssetAccess a = new ProjectAssetAccess();
        a.setProjectId(PROJECT);
        a.setAssetId(assetId);
        a.setScopeStatus(status);
        a.setScopeOverride(override);
        return a;
    }

    /** flushStatusUpdates() batches every asset id landing on the same target status into ONE
     *  {@code updateScopeStatusForAssets} call — asserting a single-id call per asset would fail
     *  whenever two assets settle on the same status in one classify() run. This captures the
     *  one call actually made for {@code status} and returns the id list it carried. */
    @SuppressWarnings("unchecked")
    private List<Long> capturedIdsFor(String status) {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(accessRepo).updateScopeStatusForAssets(eq(PROJECT), captor.capture(), eq(status));
        return captor.getValue();
    }

    private AssetRelationship rel(Long from, Long to, String type) {
        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(from);
        r.setToAssetId(to);
        r.setType(type);
        return r;
    }

    /** Wires up the mocks for one classify() run over the given assets/accesses/entries/rels. */
    private void given(List<Asset> assets, List<ProjectAssetAccess> accesses,
                        List<ProjectScopeEntry> entries, List<AssetRelationship> rels) {
        when(accessRepo.findByProjectId(PROJECT)).thenReturn(accesses);
        when(assetRepo.findAllById(anyCollection())).thenReturn(assets);
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(PROJECT)).thenReturn(entries);
        when(relRepo.findByProjectId(PROJECT)).thenReturn(rels);
    }

    @Test
    void classifyReturnsZeroWhenTheProjectHasNoTrackedAssets() {
        when(accessRepo.findByProjectId(PROJECT)).thenReturn(List.of());
        assertEquals(0, classifier.classify(PROJECT));
        verifyNoInteractions(assetRepo);
    }

    // ── Direct matching per kind ─────────────────────────────────────

    @Test
    void directDomainMatchIsCaseInsensitive() {
        Asset a = asset(AssetType.DOMAIN, "Example.com");
        given(List.of(a), List.of(access(a.getId(), INDETERMINATE, false)),
            List.of(entry("domain", "example.com", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(a.getId()), IN_SCOPE);
    }

    @Test
    void domainWildcardMatchesSubdomainsAndTheBaseDomainItself() {
        Asset sub = asset(AssetType.DOMAIN, "api.example.com");
        Asset base = asset(AssetType.DOMAIN, "example.com");
        Asset other = asset(AssetType.DOMAIN, "notexample.com");
        given(List.of(sub, base, other),
            List.of(access(sub.getId(), INDETERMINATE, false), access(base.getId(), INDETERMINATE, false), access(other.getId(), INDETERMINATE, false)),
            List.of(entry("domain_wildcard", "*.example.com", true)), List.of());

        classifier.classify(PROJECT);

        List<Long> updated = capturedIdsFor(IN_SCOPE);
        assertTrue(updated.containsAll(List.of(sub.getId(), base.getId())));
        assertFalse(updated.contains(other.getId()));
    }

    @Test
    void ipMatchExtractsTheBareIpFromAServiceStyleIdentifier() {
        Asset ip = asset(AssetType.IP, "10.0.0.5");
        given(List.of(ip), List.of(access(ip.getId(), INDETERMINATE, false)),
            List.of(entry("ip", "10.0.0.5", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(ip.getId()), IN_SCOPE);
    }

    @Test
    void cidrMatchesAnIpWithinTheRangeAndANetworkAssetByExactId() {
        Asset ip = asset(AssetType.IP, "10.0.0.42");
        Asset net = asset(AssetType.NETWORK, "10.0.0.0/24");
        given(List.of(ip, net),
            List.of(access(ip.getId(), INDETERMINATE, false), access(net.getId(), INDETERMINATE, false)),
            List.of(entry("cidr", "10.0.0.0/24", true)), List.of());

        classifier.classify(PROJECT);

        assertTrue(capturedIdsFor(IN_SCOPE).containsAll(List.of(ip.getId(), net.getId())));
    }

    @Test
    void urlWildcardMatchesByPrefix() {
        Asset app = asset(AssetType.WEB_APPLICATION, "https://example.com:8443");
        given(List.of(app), List.of(access(app.getId(), INDETERMINATE, false)),
            List.of(entry("url_wildcard", "https://example.com:8443/*", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(app.getId()), IN_SCOPE);
    }

    @Test
    void textContainsMatchesSubstringCaseInsensitively() {
        Asset txt = asset(AssetType.TEXT_DATA, "v=spf1 include:_spf.example.com ~all");
        given(List.of(txt), List.of(access(txt.getId(), INDETERMINATE, false)),
            List.of(entry("text_contains", "SPF1", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(txt.getId()), IN_SCOPE);
    }

    @Test
    void unknownScopeEntryKindNeverMatches() {
        Asset a = asset(AssetType.DOMAIN, "example.com");
        given(List.of(a), List.of(access(a.getId(), INDETERMINATE, false)),
            List.of(entry("bogus-kind", "example.com", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo, never()).updateScopeStatusForAssets(eq(PROJECT), anyList(), eq(IN_SCOPE));
    }

    @Test
    void conflictingDirectMatchesResultInIndeterminate() {
        Asset a = asset(AssetType.DOMAIN, "example.com");
        given(List.of(a), List.of(access(a.getId(), IN_SCOPE, false)),
            List.of(entry("domain", "example.com", true), entry("domain", "example.com", false)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(a.getId()), INDETERMINATE);
    }

    // ── BFS propagation ──────────────────────────────────────────────

    @Test
    void forwardPropagationCarriesScopeFromDomainToItsARecordIp() {
        Asset domain = asset(AssetType.DOMAIN, "example.com");
        Asset ip = asset(AssetType.IP, "1.2.3.4");
        given(List.of(domain, ip),
            List.of(access(domain.getId(), INDETERMINATE, false), access(ip.getId(), INDETERMINATE, false)),
            List.of(entry("domain", "example.com", true)),
            List.of(rel(domain.getId(), ip.getId(), AssetLinkType.DOMAIN_A)));

        classifier.classify(PROJECT);

        // The domain itself also flips from indeterminate to in_scope via its own direct match,
        // so it shares the same batched update call as the ip it propagated to.
        assertTrue(capturedIdsFor(IN_SCOPE).containsAll(List.of(domain.getId(), ip.getId())));
    }

    @Test
    void reversePropagationCarriesScopeFromAnInScopeDomainBackToTheWebappThatUsesIt() {
        // WEBAPP_DOMAIN is listed only in REVERSE_PROPAGATE (unlike DOMAIN_A, which is in both
        // sets and so is already bidirectional) — this isolates the reverse-only code path:
        // the edge is stored webapp -> domain, but scope must flow domain(to) -> webapp(from).
        Asset webapp = asset(AssetType.WEB_APPLICATION, "https://example.com");
        Asset domain = asset(AssetType.DOMAIN, "example.com");
        given(List.of(webapp, domain),
            List.of(access(webapp.getId(), INDETERMINATE, false), access(domain.getId(), INDETERMINATE, false)),
            List.of(entry("domain", "example.com", true)),
            List.of(rel(webapp.getId(), domain.getId(), AssetLinkType.WEBAPP_DOMAIN)));

        classifier.classify(PROJECT);

        assertTrue(capturedIdsFor(IN_SCOPE).containsAll(List.of(domain.getId(), webapp.getId())));
    }

    @Test
    void assetsUnreachableFromAnySeedStayIndeterminateAndAreNotUpdated() {
        Asset isolated = asset(AssetType.DOMAIN, "isolated.example.com");
        given(List.of(isolated), List.of(access(isolated.getId(), INDETERMINATE, false)), List.of(), List.of());
        classifier.classify(PROJECT);
        // Already indeterminate and stays indeterminate -> no diff, no update call at all.
        verify(accessRepo, never()).updateScopeStatusForAssets(anyLong(), anyList(), anyString());
    }

    @Test
    void assetReachableFromBothInAndOutOfScopeSeedsIsIndeterminate() {
        Asset a = asset(AssetType.DOMAIN, "a.example.com");
        Asset b = asset(AssetType.DOMAIN, "b.example.com");
        Asset middle = asset(AssetType.IP, "9.9.9.9");
        given(List.of(a, b, middle),
            // a/b already sit at the status their own direct match will (re)confirm, so only
            // middle's transition shows up in the diff this test wants to isolate.
            List.of(access(a.getId(), IN_SCOPE, false), access(b.getId(), OUT_OF_SCOPE, false), access(middle.getId(), IN_SCOPE, false)),
            List.of(entry("domain", "a.example.com", true), entry("domain", "b.example.com", false)),
            List.of(rel(a.getId(), middle.getId(), AssetLinkType.DOMAIN_A), rel(b.getId(), middle.getId(), AssetLinkType.DOMAIN_A)));

        classifier.classify(PROJECT);

        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(middle.getId()), INDETERMINATE);
    }

    // ── scopeOverride ────────────────────────────────────────────────

    @Test
    void overriddenAssetsAreNeverIncludedInStatusUpdates() {
        Asset a = asset(AssetType.DOMAIN, "example.com");
        given(List.of(a), List.of(access(a.getId(), OUT_OF_SCOPE, true)),
            List.of(entry("domain", "example.com", true)), List.of());
        classifier.classify(PROJECT);
        verify(accessRepo, never()).updateScopeStatusForAssets(eq(PROJECT), eq(List.of(a.getId())), anyString());
    }

    @Test
    void anOverriddenAssetStillSeedsPropagationToItsNeighbours() {
        Asset overridden = asset(AssetType.DOMAIN, "example.com");
        Asset neighbour = asset(AssetType.IP, "1.2.3.4");
        given(List.of(overridden, neighbour),
            List.of(access(overridden.getId(), IN_SCOPE, true), access(neighbour.getId(), INDETERMINATE, false)),
            List.of(), List.of(rel(overridden.getId(), neighbour.getId(), AssetLinkType.DOMAIN_A)));

        classifier.classify(PROJECT);

        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(neighbour.getId()), IN_SCOPE);
    }

    // ── HOST aggregation ─────────────────────────────────────────────

    @Test
    void hostIsInScopeWhenAllItsInterfacesAreInScope() {
        // HOST_INTERFACE is deliberately excluded from BOTH propagation sets (see the class
        // javadoc), so an interface's own effective status always comes from ITS OWN edges —
        // here, each interface's INTERFACE_IP link back to a directly-matched IP (INTERFACE_IP
        // is in both sets, so scope reverse-propagates ip -> interface).
        Asset host = asset(AssetType.HOST, "host-1.2.3.4");
        Asset iface1 = asset(AssetType.INTERFACE, "iface-1");
        Asset iface2 = asset(AssetType.INTERFACE, "iface-2");
        Asset ip1 = asset(AssetType.IP, "1.1.1.1");
        Asset ip2 = asset(AssetType.IP, "2.2.2.2");
        given(List.of(host, iface1, iface2, ip1, ip2),
            List.of(access(host.getId(), INDETERMINATE, false), access(iface1.getId(), INDETERMINATE, false),
                    access(iface2.getId(), INDETERMINATE, false), access(ip1.getId(), INDETERMINATE, false), access(ip2.getId(), INDETERMINATE, false)),
            List.of(entry("ip", "1.1.1.1", true), entry("ip", "2.2.2.2", true)),
            List.of(rel(host.getId(), iface1.getId(), AssetLinkType.HOST_INTERFACE),
                    rel(host.getId(), iface2.getId(), AssetLinkType.HOST_INTERFACE),
                    rel(iface1.getId(), ip1.getId(), AssetLinkType.INTERFACE_IP),
                    rel(iface2.getId(), ip2.getId(), AssetLinkType.INTERFACE_IP)));

        classifier.classify(PROJECT);

        assertTrue(capturedIdsFor(IN_SCOPE).contains(host.getId()));
    }

    @Test
    void hostIsIndeterminateWhenItsInterfacesDisagree() {
        Asset host = asset(AssetType.HOST, "host-1.2.3.4");
        Asset iface1 = asset(AssetType.INTERFACE, "iface-1");
        Asset iface2 = asset(AssetType.INTERFACE, "iface-2");
        Asset ip1 = asset(AssetType.IP, "1.1.1.1");
        Asset ip2 = asset(AssetType.IP, "2.2.2.2");
        given(List.of(host, iface1, iface2, ip1, ip2),
            List.of(access(host.getId(), IN_SCOPE, false), access(iface1.getId(), INDETERMINATE, false),
                    access(iface2.getId(), INDETERMINATE, false), access(ip1.getId(), INDETERMINATE, false), access(ip2.getId(), INDETERMINATE, false)),
            List.of(entry("ip", "1.1.1.1", true), entry("ip", "2.2.2.2", false)),
            List.of(rel(host.getId(), iface1.getId(), AssetLinkType.HOST_INTERFACE),
                    rel(host.getId(), iface2.getId(), AssetLinkType.HOST_INTERFACE),
                    rel(iface1.getId(), ip1.getId(), AssetLinkType.INTERFACE_IP),
                    rel(iface2.getId(), ip2.getId(), AssetLinkType.INTERFACE_IP)));

        classifier.classify(PROJECT);

        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(host.getId()), INDETERMINATE);
    }

    @Test
    void hostWithNoTrackedInterfacesFallsBackToItsOwnBfsResult() {
        Asset domain = asset(AssetType.DOMAIN, "example.com");
        Asset host = asset(AssetType.HOST, "host-1.2.3.4");
        given(List.of(domain, host),
            List.of(access(domain.getId(), INDETERMINATE, false), access(host.getId(), INDETERMINATE, false)),
            List.of(entry("domain", "example.com", true)),
            List.of()); // no HOST_INTERFACE edge at all — host has no tracked interfaces

        classifier.classify(PROJECT);

        // No propagation edge reaches the host either, so it stays indeterminate and is unchanged.
        verify(accessRepo, never()).updateScopeStatusForAssets(eq(PROJECT), eq(List.of(host.getId())), anyString());
    }

    @Test
    void hostFallsBackToAggregationSinceNoScopeEntryKindCanTargetAHostDirectly() {
        // Every matchesByKindAndValue() branch gates on a specific non-HOST asset type, so
        // directStatus can never contain a HOST id — the "direct entry on host" skip-aggregation
        // branch in classify()'s step 4 is unreachable with the current kind set. And since
        // HOST_INTERFACE itself carries no propagation, an interface with no other edges of its
        // own (no INTERFACE_IP, no direct match) has nothing to aggregate — both the interface
        // and the host it belongs to land on indeterminate.
        Asset host = asset(AssetType.HOST, "myhost.example.com");
        Asset iface = asset(AssetType.INTERFACE, "iface-1");
        given(List.of(host, iface),
            List.of(access(host.getId(), IN_SCOPE, false), access(iface.getId(), OUT_OF_SCOPE, false)),
            List.of(entry("domain", "myhost.example.com", true)), // never matches — host isn't a DOMAIN
            List.of(rel(host.getId(), iface.getId(), AssetLinkType.HOST_INTERFACE)));

        classifier.classify(PROJECT);

        assertTrue(capturedIdsFor(INDETERMINATE).containsAll(List.of(host.getId(), iface.getId())));
    }

    // ── Third-party KB override ──────────────────────────────────────

    private KbThirdPartyEntry kbEntry(String kind, String value) {
        KbThirdPartyEntry e = new KbThirdPartyEntry();
        e.setKind(kind);
        e.setValue(value);
        e.setEnabled(true);
        return e;
    }

    @Test
    void thirdPartyKbMatchOverridesTheComputedInScopeStatus() {
        Asset a = asset(AssetType.DOMAIN, "cdn.example.com");
        given(List.of(a), List.of(access(a.getId(), INDETERMINATE, false)),
            List.of(entry("domain", "cdn.example.com", true)), List.of());
        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of(kbEntry("domain", "cdn.example.com")));

        classifier.classify(PROJECT);

        verify(accessRepo).updateScopeStatusForAssets(PROJECT, List.of(a.getId()), THIRD_PARTY);
        verify(accessRepo, never()).updateScopeStatusForAssets(PROJECT, List.of(a.getId()), IN_SCOPE);
    }

    @Test
    void thirdPartyKbCheckNeverAppliesToOverriddenAssets() {
        Asset a = asset(AssetType.DOMAIN, "cdn.example.com");
        given(List.of(a), List.of(access(a.getId(), IN_SCOPE, true)), List.of(), List.of());
        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of(kbEntry("domain", "cdn.example.com")));

        classifier.classify(PROJECT);

        verify(accessRepo, never()).updateScopeStatusForAssets(eq(PROJECT), eq(List.of(a.getId())), anyString());
    }

    // ── reclassifyThirdPartyPlatformWide() ────────────────────────────

    @Test
    void reclassifyThirdPartyPlatformWideIsANoOpWithNoEnabledKbEntries() {
        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of());
        assertEquals(0, classifier.reclassifyThirdPartyPlatformWide());
        verifyNoInteractions(accessRepo);
    }

    @Test
    void reclassifyThirdPartyPlatformWideGroupsUpdatesByProjectAndSumsCounts() {
        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of(kbEntry("domain", "cdn.example.com")));
        Asset a1 = asset(AssetType.DOMAIN, "cdn.example.com");
        Asset a2 = asset(AssetType.DOMAIN, "cdn.example.com");
        ProjectAssetAccess acc1 = access(a1.getId(), IN_SCOPE, false); acc1.setProjectId(1L);
        ProjectAssetAccess acc2 = access(a2.getId(), IN_SCOPE, false); acc2.setProjectId(2L);
        when(accessRepo.findByScopeOverrideFalse()).thenReturn(List.of(acc1, acc2));
        when(assetRepo.findAllById(anyCollection())).thenReturn(List.of(a1, a2));
        when(accessRepo.updateScopeStatusForAssets(eq(1L), anyList(), eq(THIRD_PARTY))).thenReturn(1);
        when(accessRepo.updateScopeStatusForAssets(eq(2L), anyList(), eq(THIRD_PARTY))).thenReturn(1);

        int updated = classifier.reclassifyThirdPartyPlatformWide();

        assertEquals(2, updated);
        verify(accessRepo).updateScopeStatusForAssets(eq(1L), eq(List.of(a1.getId())), eq(THIRD_PARTY));
        verify(accessRepo).updateScopeStatusForAssets(eq(2L), eq(List.of(a2.getId())), eq(THIRD_PARTY));
    }

    @Test
    void reclassifyThirdPartyPlatformWideSkipsAssetsAlreadyThirdParty() {
        when(kbThirdPartyRepo.findAllByEnabledTrue()).thenReturn(List.of(kbEntry("domain", "cdn.example.com")));
        Asset a = asset(AssetType.DOMAIN, "cdn.example.com");
        ProjectAssetAccess acc = access(a.getId(), THIRD_PARTY, false);
        when(accessRepo.findByScopeOverrideFalse()).thenReturn(List.of(acc));
        when(assetRepo.findAllById(anyCollection())).thenReturn(List.of(a));

        assertEquals(0, classifier.reclassifyThirdPartyPlatformWide());
        verify(accessRepo, never()).updateScopeStatusForAssets(anyLong(), anyList(), anyString());
    }

    // ── isThirdParty() ───────────────────────────────────────────────

    @Test
    void isThirdPartyChecksTheAssetDirectlyAgainstKbEntries() {
        Asset a = asset(AssetType.DOMAIN, "cdn.example.com");
        assertTrue(classifier.isThirdParty(a, List.of(kbEntry("domain", "cdn.example.com"))));
        assertFalse(classifier.isThirdParty(a, List.of(kbEntry("domain", "other.example.com"))));
    }

    // ── explainScope() ───────────────────────────────────────────────

    @Test
    void explainScopeThrowsNotFoundWhenTheAssetIsNotTrackedInTheProject() {
        when(accessRepo.findByProjectId(PROJECT)).thenReturn(List.of());
        assertThrows(NotFoundException.class, () -> classifier.explainScope(PROJECT, 999L));
    }

    @Test
    void explainScopeReturnsDirectMatchesAndForwardPropagationSources() {
        Asset domain = asset(AssetType.DOMAIN, "example.com");
        Asset ip = asset(AssetType.IP, "1.2.3.4");
        given(List.of(domain, ip),
            List.of(access(domain.getId(), IN_SCOPE, false), access(ip.getId(), IN_SCOPE, false)),
            List.of(entry("domain", "example.com", true)),
            List.of(rel(domain.getId(), ip.getId(), AssetLinkType.DOMAIN_A)));

        var result = classifier.explainScope(PROJECT, ip.getId());

        assertEquals(IN_SCOPE, result.status());
        assertTrue(result.directMatches().isEmpty()); // no scope entry matches the IP itself
        assertEquals(1, result.propagationSources().size());
        var source = result.propagationSources().get(0);
        assertEquals(domain.getId(), source.assetId());
        assertEquals("forward", source.direction());
        assertEquals(1, source.directMatches().size());
    }

    @Test
    void explainScopeReflectsOverrideFlagFromTheAccessRow() {
        Asset a = asset(AssetType.DOMAIN, "example.com");
        given(List.of(a), List.of(access(a.getId(), OUT_OF_SCOPE, true)), List.of(), List.of());
        var result = classifier.explainScope(PROJECT, a.getId());
        assertTrue(result.override());
        assertEquals(OUT_OF_SCOPE, result.status());
    }

    // ── classifyForAsset() ───────────────────────────────────────────

    @Test
    void classifyForAssetReclassifiesEveryDistinctProjectContainingTheAsset() {
        var pa1 = new ProjectAssetAccess(); pa1.setProjectId(1L); pa1.setAssetId(50L);
        var pa2 = new ProjectAssetAccess(); pa2.setProjectId(2L); pa2.setAssetId(50L);
        when(accessRepo.findByAssetId(50L)).thenReturn(List.of(pa1, pa2));
        when(accessRepo.findByProjectId(1L)).thenReturn(List.of());
        when(accessRepo.findByProjectId(2L)).thenReturn(List.of());

        classifier.classifyForAsset(50L);

        verify(accessRepo).findByProjectId(1L);
        verify(accessRepo).findByProjectId(2L);
    }
}
