package com.martecyber.ares.net;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * Routes every connection through a SOCKS5 proxy (host/port read from the {@code
 * ARES_SOCKS_PROXY_HOST}/{@code ARES_SOCKS_PROXY_PORT} env vars) instead of connecting directly —
 * a generic transport primitive, not tied to any specific proxy implementation or deployment.
 * One real use case: a NetBird client running in userspace/netstack mode (no TUN device, so it
 * exposes a local SOCKS5 proxy instead) on a sandboxed container host that can't grant NET_ADMIN,
 * letting the JVM still reach services that only exist on that private network.
 *
 * <p>Env vars rather than Spring-managed config because pgjdbc's {@code socketFactory=} JDBC URL
 * parameter instantiates this class by reflection via a public no-arg constructor, entirely
 * outside Spring's lifecycle — there's no way to inject a {@code @Value} into it. The same class
 * is reused for direct, explicit construction too (see {@code SmbStorageService}).
 */
public class Socks5SocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() {
        return new Socket(proxy());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket(proxy());
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket socket = new Socket(proxy());
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = new Socket(proxy());
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket socket = new Socket(proxy());
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    private static Proxy proxy() {
        String host = requireEnv("ARES_SOCKS_PROXY_HOST");
        int port = Integer.parseInt(requireEnv("ARES_SOCKS_PROXY_PORT"));
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                name + " must be set to use " + Socks5SocketFactory.class.getSimpleName());
        }
        return value;
    }
}
