package com.martecyber.ares.assets;

import com.martecyber.ares.aql.compile.AqlVariableExpander;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.tags.TagRepository;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** IP → network (NETWORK_IP) and network → subnet (NETWORK_SUBNET) auto-linking, IPv4 and IPv6. */
class AssetNetworkLinkingTest {

    private AssetRepository repo;
    private AssetRelationshipService relSvc;
    private AssetService service;
    private final List<Asset> networks = new ArrayList<>();
    private final List<Asset> ips = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(AssetRepository.class);
        relSvc = mock(AssetRelationshipService.class);
        AssetRelationshipRepository relRepo = mock(AssetRelationshipRepository.class);
        service = new AssetService(repo, relSvc, relRepo,
            mock(OrganizationRepository.class), mock(AssetScopeClassifier.class), mock(ScopeClassifyScheduler.class),
            mock(DetectionRepository.class), mock(AssetTagRepository.class),
            mock(TagRepository.class), mock(OrgScopeService.class), mock(AssetAqlRegistry.class),
            mock(WorkflowEventDispatcher.class), mock(AqlVariableExpander.class));
        when(repo.findByOrganizationIdAndType(1L, AssetType.NETWORK)).thenReturn(networks);
        when(repo.findByOrganizationIdAndType(1L, AssetType.IP)).thenReturn(ips);
        when(relRepo.findByToAssetIdAndType(anyLong(), anyString())).thenReturn(List.of());
    }

    private Asset add(List<Asset> into, long id, String type, String identifier) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOrganizationId(1L);
        a.setType(type);
        a.setIdentifier(identifier);
        into.add(a);
        return a;
    }

    @Test
    void ipv6IpLinksToItsMostSpecificNetwork() {
        add(networks, 1, AssetType.NETWORK, "2001:db8::/32");
        add(networks, 2, AssetType.NETWORK, "2001:db8:1::/48");
        add(networks, 3, AssetType.NETWORK, "10.0.0.0/8");
        Asset ip = add(ips, 10, AssetType.IP, "2001:db8:1::42");

        service.autoLinkIpToNetwork(1L, ip);

        verify(relSvc).linkIfAbsent(2L, 10L, AssetLinkType.NETWORK_IP);
        verify(relSvc, times(1)).linkIfAbsent(anyLong(), anyLong(), anyString());
    }

    @Test
    void ipv4IpStillLinksAndNeverMatchesAnIpv6Network() {
        add(networks, 1, AssetType.NETWORK, "::/0");
        add(networks, 2, AssetType.NETWORK, "192.168.1.0/24");
        Asset ip = add(ips, 10, AssetType.IP, "192.168.1.7");

        service.autoLinkIpToNetwork(1L, ip);

        verify(relSvc).linkIfAbsent(2L, 10L, AssetLinkType.NETWORK_IP);
        verify(relSvc, never()).linkIfAbsent(eq(1L), anyLong(), anyString());
    }

    @Test
    void newIpv6NetworkLinksUnderItsParentAndAdoptsContainedIps() {
        add(networks, 1, AssetType.NETWORK, "2001:db8::/32");
        Asset sub = add(networks, 2, AssetType.NETWORK, "2001:db8:1::/48");
        add(ips, 10, AssetType.IP, "2001:db8:1::42");
        add(ips, 11, AssetType.IP, "2001:db8:2::1");

        service.autoLinkNetwork(1L, sub);

        verify(relSvc).linkIfAbsent(1L, 2L, AssetLinkType.NETWORK_SUBNET);
        verify(relSvc).linkIfAbsent(2L, 10L, AssetLinkType.NETWORK_IP);
        verify(relSvc, never()).linkIfAbsent(2L, 11L, AssetLinkType.NETWORK_IP);
    }
}
