package com.martecyber.ares.common;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * IPv4 + IPv6 address and CIDR comparisons on the raw address bytes, so textual variants of the
 * same IPv6 address ("2001:db8::1" vs "2001:0DB8:0:0::1") compare equal. Never resolves DNS:
 * every input goes through {@link IpValidator#isValidLiteral} before {@link InetAddress} parsing.
 * Addresses of different families never match each other.
 */
public final class IpCidr {

    private IpCidr() {}

    /** The address bytes (4 for IPv4, 16 for IPv6), or null when {@code ip} is not an IP literal. */
    public static byte[] toBytes(String ip) {
        if (ip == null) return null;
        String v = ip.trim();
        if (!IpValidator.isValidLiteral(v)) return null;
        try {
            return InetAddress.getByName(v).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when both are IP literals denoting the same address. */
    public static boolean sameAddress(String a, String b) {
        byte[] x = toBytes(a);
        return x != null && Arrays.equals(x, toBytes(b));
    }

    /** Prefix length of "addr/prefix", or -1 when malformed or out of range for its family. */
    public static int prefixLength(String cidr) {
        if (cidr == null) return -1;
        int slash = cidr.indexOf('/');
        if (slash < 0) return -1;
        byte[] base = toBytes(cidr.substring(0, slash));
        if (base == null) return -1;
        try {
            int prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            return prefix >= 0 && prefix <= base.length * 8 ? prefix : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when {@code ip} lies within {@code cidr} ("addr/prefix"); false for any malformed input. */
    public static boolean contains(String cidr, String ip) {
        int prefix = prefixLength(cidr);
        if (prefix < 0) return false;
        byte[] net = toBytes(cidr.substring(0, cidr.indexOf('/')));
        byte[] addr = toBytes(ip);
        if (addr == null || addr.length != net.length) return false;
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (net[i] != addr[i]) return false;
        }
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
    }

    /** True when both CIDRs denote the same block (same family, prefix and masked network). */
    public static boolean sameNetwork(String a, String b) {
        int pa = prefixLength(a);
        if (pa < 0 || pa != prefixLength(b)) return false;
        return contains(a, b.substring(0, b.indexOf('/'))) && contains(b, a.substring(0, a.indexOf('/')));
    }
}
