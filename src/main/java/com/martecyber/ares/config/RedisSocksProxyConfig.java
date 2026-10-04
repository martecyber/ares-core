package com.martecyber.ares.config;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.channel.Channel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/**
 * Routes Redis connections through a SOCKS5 proxy when {@code ares.network.socks-proxy.enabled}
 * is set — see {@link com.martecyber.ares.net.Socks5SocketFactory}'s doc comment for why this
 * exists. Lettuce has no {@code SocketFactory}-style hook like pgjdbc/smbj; its own extension
 * point for this is a {@link NettyCustomizer} on {@link ClientResources}, which Spring Boot's
 * Redis auto-configuration picks up automatically once a {@link ClientResources} bean is present
 * in the context, instead of building its own default one.
 */
@Configuration
@ConditionalOnProperty(name = "ares.network.socks-proxy.enabled", havingValue = "true")
public class RedisSocksProxyConfig {

    @Value("${ares.network.socks-proxy.host}")
    private String proxyHost;

    @Value("${ares.network.socks-proxy.port}")
    private int proxyPort;

    @Bean
    public ClientResources clientResources() {
        InetSocketAddress proxyAddress = new InetSocketAddress(proxyHost, proxyPort);
        return DefaultClientResources.builder()
            .nettyCustomizer(new NettyCustomizer() {
                @Override
                public void afterChannelInitialized(Channel channel) {
                    channel.pipeline().addFirst(new Socks5ProxyHandler(proxyAddress));
                }
            })
            .build();
    }
}
