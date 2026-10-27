package io.github.rennaraujo.sage.web;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Decide se um endereço resolvido pode receber uma conexão de saída. Bloqueia o que não é Internet pública: loopback,
 * não especificado, link-local (inclui os metadados de nuvem em 169.254.169.254), rede privada (RFC 1918 e fc00::/7),
 * multicast, CGNAT, faixas reservadas e os formatos IPv6 que embutem um IPv4 (mapeado ::ffff:a.b.c.d, compatível, NAT64
 * 64:ff9b::/96 e 6to4 2002::/16), que são verificados pelo IPv4 que carregam.
 */
final class PublicAddresses {

    private static final int IPV6_BYTES = 16;
    private static final int BYTE_MASK = 0xFF;
    private static final int MAPPED_MARK = 0xFF;
    private static final int ULA_MASK = 0xFE;
    private static final int ULA_PREFIX = 0xFC;
    private static final int NAT64_HIGH = 0x00;
    private static final int NAT64_HIGH_BYTE_2 = 0x64;
    private static final int NAT64_BYTE_3 = 0xFF;
    private static final int NAT64_BYTE_4 = 0x9B;
    private static final int SIXTO4_HIGH = 0x20;
    private static final int SIXTO4_LOW = 0x02;
    private static final int CGNAT_SECOND_MASK = 0xC0;
    private static final int CGNAT_SECOND = 64;
    private static final int BENCHMARK_FIRST = 198;
    private static final int BENCHMARK_SECOND_MASK = 0xFE;
    private static final int BENCHMARK_SECOND = 18;
    private static final int RESERVED_FIRST = 240;
    private static final int IETF_FIRST = 192;
    private static final int IETF_THIRD = 0;
    private static final int CGNAT_FIRST = 100;
    private static final int V4_OFFSET_IN_V6 = 12;
    private static final int NAT64_V4_OFFSET = 12;
    private static final int SIXTO4_V4_OFFSET = 2;
    private static final int V4_BYTES = 4;

    private PublicAddresses() {}

    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || raw.length == V4_BYTES) {
            return isReservedV4(raw, 0);
        }
        if (raw.length != IPV6_BYTES) {
            return true;
        }
        if ((raw[0] & ULA_MASK) == ULA_PREFIX) {
            return true;
        }
        return isReservedV6(raw) || embedsV4(raw);
    }

    /** Teredo (2001::/32), documentação (2001:db8::/32), descarte (100::/64) e NAT64 de uso local (64:ff9b:1::/48). */
    private static boolean isReservedV6(byte[] raw) {
        boolean teredo = startsWith(raw, 0x20, 0x01, 0x00, 0x00);
        boolean documentation = startsWith(raw, 0x20, 0x01, 0x0d, 0xb8);
        boolean discard = startsWith(raw, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        boolean localNat64 = startsWith(raw, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x01);
        return teredo || documentation || discard || localNat64;
    }

    private static boolean startsWith(byte[] raw, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((raw[i] & BYTE_MASK) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** IPv6 com IPv4 dentro: bloqueia se o IPv4 embutido é bloqueado. */
    private static boolean embedsV4(byte[] raw) {
        boolean zeroPrefix = true;
        for (int i = 0; i < V4_OFFSET_IN_V6 - 2; i++) {
            zeroPrefix &= raw[i] == 0;
        }
        boolean mapped = zeroPrefix && (raw[10] & BYTE_MASK) == MAPPED_MARK && (raw[11] & BYTE_MASK) == MAPPED_MARK;
        boolean compatible = zeroPrefix && raw[10] == 0 && raw[11] == 0;
        if (mapped || compatible) {
            return isReservedV4(raw, V4_OFFSET_IN_V6) || isLoopbackOrAnyV4(raw, V4_OFFSET_IN_V6);
        }
        boolean nat64 = (raw[0] & BYTE_MASK) == NAT64_HIGH
                && (raw[1] & BYTE_MASK) == NAT64_HIGH_BYTE_2
                && (raw[2] & BYTE_MASK) == NAT64_BYTE_3
                && (raw[3] & BYTE_MASK) == NAT64_BYTE_4;
        if (nat64) {
            return isReservedV4(raw, NAT64_V4_OFFSET) || isLoopbackOrAnyV4(raw, NAT64_V4_OFFSET);
        }
        boolean sixToFour = (raw[0] & BYTE_MASK) == SIXTO4_HIGH && (raw[1] & BYTE_MASK) == SIXTO4_LOW;
        if (sixToFour) {
            return isReservedV4(raw, SIXTO4_V4_OFFSET) || isLoopbackOrAnyV4(raw, SIXTO4_V4_OFFSET);
        }
        return false;
    }

    private static boolean isLoopbackOrAnyV4(byte[] raw, int offset) {
        try {
            byte[] v4 = new byte[V4_BYTES];
            System.arraycopy(raw, offset, v4, 0, V4_BYTES);
            InetAddress embedded = InetAddress.getByAddress(v4);
            return embedded.isAnyLocalAddress()
                    || embedded.isLoopbackAddress()
                    || embedded.isLinkLocalAddress()
                    || embedded.isSiteLocalAddress()
                    || embedded.isMulticastAddress();
        } catch (java.net.UnknownHostException e) {
            return true;
        }
    }

    /** Faixas IPv4 que as checagens do JDK não cobrem: 0/8, CGNAT, 192.0.0.0/24, benchmark e reservado (240/4). */
    private static boolean isReservedV4(byte[] raw, int offset) {
        int a = raw[offset] & BYTE_MASK;
        int b = raw[offset + 1] & BYTE_MASK;
        int c = raw[offset + 2] & BYTE_MASK;
        return a == 0
                || (a == CGNAT_FIRST && (b & CGNAT_SECOND_MASK) == CGNAT_SECOND)
                || (a == IETF_FIRST && b == 0 && c == IETF_THIRD)
                || (a == BENCHMARK_FIRST && (b & BENCHMARK_SECOND_MASK) == BENCHMARK_SECOND)
                || a >= RESERVED_FIRST;
    }
}
