package com.martecyber.ares.config;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Proxy;

/**
 * Routes Sentry/GlitchTip event reporting through the SOCKS5 proxy too, when enabled — see
 * {@link com.martecyber.ares.net.Socks5SocketFactory}'s doc comment for why this exists.
 * {@code SentryOptions} itself supports a proxy with a {@code type} (HTTP/SOCKS) directly, but
 * the Spring Boot starter's own {@code SentryProperties} binds it unconditionally if set at all
 * — setting {@code sentry.proxy.host} from the same {@code ARES_SOCKS_PROXY_HOST} env var
 * (blank when the proxy is disabled) would hand Sentry a broken, empty proxy address even on a
 * deployment that never needed one. The starter's own {@code OptionsConfiguration} customization
 * hook avoids that: wired here only when the proxy is actually enabled, left untouched otherwise.
 */
@Configuration
@ConditionalOnProperty(name = "ares.network.socks-proxy.enabled", havingValue = "true")
public class SentrySocksProxyConfig {

    @Value("${ares.network.socks-proxy.host}")
    private String proxyHost;

    @Value("${ares.network.socks-proxy.port}")
    private String proxyPort;

    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> sentrySocksProxyOptions() {
        return options -> options.setProxy(new SentryOptions.Proxy(proxyHost, proxyPort, Proxy.Type.SOCKS));
    }
}
