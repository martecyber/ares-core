package com.martecyber.ares.projects;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The compiled matcher must agree with the single-entry reference rule on every pair. */
class ScopeMatcherTest {

    private static final String[][] ENTRIES = {
        {"domain", "Example.com"}, {"domain_wildcard", "*.corp.example.org"}, {"domain_wildcard", "shop.net"},
        {"domain_wildcard", "*."}, {"domain_wildcard", ".odd.com"},
        {"url", "https://App.example.com"}, {"url_wildcard", "https://portal.example.com/api/*"},
        {"url_wildcard", "https://wild.example.com*"},
        {"ip", "10.0.0.5"}, {"ip", "2001:DB8::1"}, {"ip", "not-an-ip"},
        {"cidr", "192.168.0.0/16"}, {"cidr", "2001:db8:abcd::/48"}, {"cidr", "10.1.2.3/24"}, {"cidr", "garbage/99"},
        {"android_app", "com.Example.app"}, {"ios_app", "id12345"}, {"windows_app", "Example.Win"},
        {"hardware", "HW-1"}, {"text_contains", "Google-Site-Verification"},
        {"email", "Admin@Example.com"}, {"email_domain", "Partner.io"},
        {"system", "SAP ERP"}, {"directory", "Corp AD"}, {"unknown_kind", "x"},
        {"repository", "https://GitHub.com/acme/app.git"}, {"container_image", "ghcr.io/acme/api:1.2"},
    };

    private static final String[][] ASSETS = {
        {AssetType.DOMAIN, "example.com"}, {AssetType.DOMAIN, "EXAMPLE.COM"}, {AssetType.DOMAIN, "a.corp.example.org"},
        {AssetType.DOMAIN, "corp.example.org"}, {AssetType.DOMAIN, "xcorp.example.org"}, {AssetType.DOMAIN, "shop.net"},
        {AssetType.DOMAIN, "a.b.shop.net"}, {AssetType.DOMAIN, "x.odd.com"}, {AssetType.DOMAIN, "x..odd.com"},
        {AssetType.DOMAIN, "foo."}, {AssetType.DOMAIN, "other.com"},
        {AssetType.WEB_APPLICATION, "https://app.example.com"}, {AssetType.WEB_APPLICATION, "https://portal.example.com/api/v1"},
        {AssetType.WEB_APPLICATION, "https://portal.example.com/web"}, {AssetType.WEB_APPLICATION, "https://wild.example.com.evil"},
        {AssetType.IP, "10.0.0.5"}, {AssetType.IP, "10.0.0.5:443/tcp"}, {AssetType.IP, "2001:db8:0:0::1"},
        {AssetType.IP, "192.168.44.1"}, {AssetType.IP, "2001:db8:abcd:12::9"}, {AssetType.IP, "10.1.2.200"},
        {AssetType.IP, "10.1.3.1"}, {AssetType.IP, "not-an-ip"}, {AssetType.IP, "8.8.8.8"},
        {AssetType.NETWORK, "192.168.0.0/16"}, {AssetType.NETWORK, "192.168.255.255/16"}, {AssetType.NETWORK, "10.1.2.0/24"},
        {AssetType.NETWORK, "2001:DB8:ABCD::/48"}, {AssetType.NETWORK, "192.168.0.0/17"}, {AssetType.NETWORK, "garbage/99"},
        {AssetType.ANDROID_APP, "com.Example.app"}, {AssetType.ANDROID_APP, "com.example.app"},
        {AssetType.IOS_APP, "id12345"}, {AssetType.WINDOWS_APP, "Example.Win"}, {AssetType.HARDWARE, "HW-1"},
        {AssetType.TEXT_DATA, "v=spf1 google-site-verification=abc"}, {AssetType.TEXT_DATA, "v=spf1 -all"},
        {AssetType.EMAIL, "admin@example.com"}, {AssetType.EMAIL, "bob@partner.io"}, {AssetType.EMAIL, "bob@notpartner.io"},
        {AssetType.EMAIL, "weird@x@partner.io"}, {AssetType.SYSTEM, "sap erp"}, {AssetType.DIRECTORY, "CORP AD"},
        {AssetType.HOST, "example.com"},
        {AssetType.SOFTWARE, "https://github.com/acme/app"}, {AssetType.SOFTWARE, "https://github.com/acme/app/"},
        {AssetType.SOFTWARE, "https://github.com/acme/other"}, {AssetType.SOFTWARE, "ghcr.io/acme/api:1.2"},
        {AssetType.SOFTWARE, "ghcr.io/acme/api:1.3"}, {AssetType.SOFTWARE, "com.acme:app"},
    };

    private static Asset asset(String type, String id) {
        Asset a = new Asset();
        a.setType(type);
        a.setIdentifier(id);
        return a;
    }

    @Test
    void eachEntryCompiledAloneAgreesWithTheReferenceRule() {
        for (String[] e : ENTRIES) {
            ScopeMatcher m = ScopeMatcher.compile(List.<String[]>of(e));
            for (String[] a : ASSETS) {
                Asset asset = asset(a[0], a[1]);
                assertEquals(ScopeMatcher.matchesEntry(asset, e[0], e[1]), m.matches(asset),
                    () -> e[0] + "=" + e[1] + " vs " + a[0] + ":" + a[1]);
            }
        }
    }

    @Test
    void allEntriesCompiledTogetherMatchWhenAnyEntryDoes() {
        ScopeMatcher m = ScopeMatcher.compile(List.of(ENTRIES));
        int matched = 0;
        for (String[] a : ASSETS) {
            Asset asset = asset(a[0], a[1]);
            boolean expected = false;
            for (String[] e : ENTRIES) expected |= ScopeMatcher.matchesEntry(asset, e[0], e[1]);
            assertEquals(expected, m.matches(asset), () -> a[0] + ":" + a[1]);
            if (expected) matched++;
        }
        assertTrue(matched > 20, "fixture should exercise many matching kinds");
    }

    @Test
    void emptyAndNullEntriesNeverMatch() {
        List<String[]> entries = new ArrayList<>();
        entries.add(new String[]{null, "example.com"});
        entries.add(new String[]{"domain", null});
        ScopeMatcher m = ScopeMatcher.compile(entries);
        assertTrue(m.isEmpty());
        assertFalse(m.matches(asset(AssetType.DOMAIN, "example.com")));
        assertFalse(ScopeMatcher.EMPTY.matches(asset(AssetType.DOMAIN, "example.com")));
        assertFalse(ScopeMatcher.compile(List.<String[]>of(new String[]{"domain", "x.com"})).matches(asset(AssetType.DOMAIN, null)));
    }

    @Test
    void repositoryAndImageEntriesMatchSoftwareAssets() {
        ScopeMatcher m = ScopeMatcher.compile(List.<String[]>of(
            new String[]{"repository", "https://GitHub.com/acme/app.git"},
            new String[]{"container_image", "ghcr.io/acme/api:1.2"}));
        assertTrue(m.matches(asset(AssetType.SOFTWARE, "https://github.com/acme/app")));
        assertTrue(m.matches(asset(AssetType.SOFTWARE, "https://github.com/acme/app/")));
        assertTrue(m.matches(asset(AssetType.SOFTWARE, "ghcr.io/acme/api:1.2")));
        assertFalse(m.matches(asset(AssetType.SOFTWARE, "ghcr.io/acme/api:1.3")));
        assertFalse(m.matches(asset(AssetType.SOFTWARE, "com.acme:app")));
        assertFalse(m.matches(asset(AssetType.WEB_APPLICATION, "https://github.com/acme/app")));
    }
}
