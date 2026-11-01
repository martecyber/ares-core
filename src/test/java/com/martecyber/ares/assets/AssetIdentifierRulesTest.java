package com.martecyber.ares.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class AssetIdentifierRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ip              | ' 10.0.0.1 '                     | 10.0.0.1",
        "ip              | 2001:DB8::1                      | 2001:db8::1",
        "network         | 192.168.1.0 / 24                 | 192.168.1.0/24",
        "network         | 2001:db8::/32                    | 2001:db8::/32",
        "domain          | Example.COM.                     | example.com",
        "domain          | *.example.com                    | *.example.com",
        "domain          | _dmarc.example.com               | _dmarc.example.com",
        "domain          | intranet                         | intranet",
        "email           | John.Doe@Example.com             | john.doe@example.com",
        "service         | 10.0.0.1:443/TCP                 | 10.0.0.1:443/tcp",
        "service         | 2001:db8::1:53/udp               | 2001:db8::1:53/udp",
        "service         | Api.Example.com:8443/tcp         | api.example.com:8443/tcp",
        "web_application | https://App.example.com/login    | https://App.example.com/login",
        "web_endpoint    | http://10.0.0.1:8080/api?x=1     | http://10.0.0.1:8080/api?x=1",
        "host            | '  Web server 01 '               | Web server 01",
        "technology      | nginx 1.25                       | nginx 1.25",
    })
    void acceptsAndNormalizes(String type, String in, String expected) {
        assertEquals(expected, AssetIdentifierRules.normalize(type, in));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ip              | 10.0.0.256",
        "ip              | example.com",
        "network         | 10.0.0.0",
        "network         | 10.0.0.0/33",
        "network         | 2001:db8::/129",
        "network         | foo/24",
        "domain          | 10.0.0.1",
        "domain          | https://example.com",
        "domain          | exa mple.com",
        "domain          | -bad.example.com",
        "domain          | example..com",
        "email           | no-at-sign",
        "email           | @example.com",
        "email           | john@",
        "email           | jo hn@example.com",
        "email           | john@*.example.com",
        "service         | 10.0.0.1",
        "service         | 10.0.0.1:0/tcp",
        "service         | 10.0.0.1:70000/tcp",
        "web_application | example.com",
        "web_endpoint    | ftp://example.com/",
        "web_endpoint    | https:///path",
    })
    void rejectsMalformed(String type, String in) {
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize(type, in));
    }

    @Test
    void rejectsUnknownOrMissingType() {
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize("location", "x"));
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize(" ", "x"));
    }

    @Test
    void rejectsBlankControlCharsAndOverlong() {
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize("host", "   "));
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize("host", null));
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize("host", "a\nb"));
        assertThrows(IllegalArgumentException.class,
            () -> AssetIdentifierRules.normalize("host", "x".repeat(AssetIdentifierRules.MAX_LENGTH + 1)));
        assertDoesNotThrow(() -> AssetIdentifierRules.normalize("host", "x".repeat(AssetIdentifierRules.MAX_LENGTH)));
    }

    @Test
    void textDataAllowsLongTxtRecords() {
        String dkim = "v=DKIM1; k=rsa; p=" + "A".repeat(700);
        assertEquals(dkim, AssetIdentifierRules.normalize("text_data", dkim));
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalize("text_data",
            "x".repeat(AssetIdentifierRules.TEXT_DATA_MAX_LENGTH + 1)));
        assertEquals(255, AssetIdentifierRules.maxLength("domain"));
    }
}
