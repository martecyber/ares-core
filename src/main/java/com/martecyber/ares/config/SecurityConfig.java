package com.martecyber.ares.config;

import com.martecyber.ares.auth.JwtAuthenticationFilter;
import com.martecyber.ares.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Value("${ares.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOriginsRaw;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           com.martecyber.ares.auth.ApiTokenAuthFilter apiTokenFilter,
                                           com.martecyber.ares.agents.AgentTokenAuthFilter agentTokenFilter,
                                           UnauthorizedAuthenticationEntryPoint entryPoint) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/ping").permitAll()
                .requestMatchers("/api/v1/versions").permitAll()
                .requestMatchers("/api/v1/license", "/api/v1/license/text").permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/cli/**").permitAll()
                // Public: agent installer distribution (parallel to /api/v1/cli/**)
                .requestMatchers("/api/v1/agent/dist/**").permitAll()
                // Public: one-time agent enrollment (exchanges enrollment_code for a long-lived token)
                .requestMatchers(HttpMethod.POST, "/api/v1/agent/enroll").permitAll()
                // Public: inbound workflow webhook receiver — authenticated by HMAC signature
                // (WebhookSignatureService), not a session/token, since the caller is an
                // external system with no Ares account.
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/in/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/profile/*/avatar").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/organizations/*/logo").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/editor-images/*").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(agentTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(allowedOriginsRaw.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Location", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }
}
