package com.martecyber.ares.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ares.jwt")
public class JwtProperties {

    /** JWT issuer claim. */
    private String issuer = "ares-asm";

    /** HMAC-SHA256 signing secret. Must be at least 32 bytes. */
    private String secret;

    /** Access token lifetime. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Refresh token lifetime. */
    private Duration refreshTokenTtl = Duration.ofDays(30);

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }

    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
}
