package com.martecyber.ares.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IpCidrTest {

    @ParameterizedTest
    @CsvSource({
        "10.0.0.0/24,       10.0.0.42,          true",
        "10.0.0.0/24,       10.0.1.1,           false",
        "10.0.0.0/0,        8.8.8.8,            true",
        "10.0.0.5/32,       10.0.0.5,           true",
        "172.16.0.0/12,     172.31.255.255,     true",
        "172.16.0.0/12,     172.32.0.1,         false",
        "2001:db8::/32,     2001:db8:1234::1,   true",
        "2001:db8::/32,     2001:db9::1,        false",
        "2001:DB8::/32,     2001:0db8:0:0::1,   true",
        "2001:db8:abcd::/47, 2001:db8:abcd:1::1, true",
        "2001:db8:abcd::/47, 2001:db8:abce::1,  false",
        "::/0,              fe80::1,            true",
        "::/0,              10.0.0.1,           false",
        "10.0.0.0/8,        2001:db8::1,        false",
        "10.0.0.0/33,       10.0.0.1,           false",
        "2001:db8::/129,    2001:db8::1,        false",
        "not-a-net/24,      10.0.0.1,           false",
        "10.0.0.0/24,       example.com,        false",
    })
    void contains(String cidr, String ip, boolean expected) {
        assertEquals(expected, IpCidr.contains(cidr, ip));
    }

    @Test
    void prefixLengthIsFamilyAware() {
        assertEquals(24, IpCidr.prefixLength("10.0.0.0/24"));
        assertEquals(64, IpCidr.prefixLength("2001:db8::/64"));
        assertEquals(-1, IpCidr.prefixLength("10.0.0.0/64"));
        assertEquals(-1, IpCidr.prefixLength("10.0.0.0"));
    }

    @Test
    void textualVariantsOfTheSameAddressOrNetworkCompareEqual() {
        assertTrue(IpCidr.sameAddress("2001:db8::1", "2001:0DB8:0000:0:0::1"));
        assertFalse(IpCidr.sameAddress("2001:db8::1", "2001:db8::2"));
        assertFalse(IpCidr.sameAddress("example.com", "example.com"));
        assertTrue(IpCidr.sameNetwork("2001:db8::/32", "2001:0DB8:0::/32"));
        assertFalse(IpCidr.sameNetwork("2001:db8::/32", "2001:db8::/48"));
    }
}
