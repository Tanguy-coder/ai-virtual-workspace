package dev.aiw.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Session-cookie authentication for the single-page app, with CSRF protection
 * (token in the XSRF-TOKEN cookie, echoed by the client in the X-XSRF-TOKEN header).
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${aiw.web.allowed-origins}") String[] origins,
                                            @Value("${aiw.security.metrics-token:}") String metricsToken) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Prometheus reads the metrics with a bearer token (docs/18); without a configured token, nobody does.
                        .requestMatchers("/actuator/prometheus").access((a, ctx) ->
                                new org.springframework.security.authorization.AuthorizationDecision(bearerMatches(ctx.getRequest(), metricsToken)))
                        // What an invitation link is for, before signing in (the token is the secret).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/invitations/*").permitAll()
                        .requestMatchers("/api/**", "/ws/**").authenticated()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    static boolean bearerMatches(jakarta.servlet.http.HttpServletRequest request, String token) {
        if (token == null || token.isBlank()) return false;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return java.security.MessageDigest.isEqual(header.substring(7).strip().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
