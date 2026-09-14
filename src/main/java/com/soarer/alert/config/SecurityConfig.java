package com.soarer.alert.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    "/api/auth/login",
                                    "/api/auth/demo-credentials",
                                    "/api/auth/logout",
                                    "/login.html",
                                    "/login.js",
                                    "/auth.css",
                                    "/theme.css",
                                    "/theme.js",
                                    "/actuator/health/**",
                                    "/actuator/prometheus"
                            )
                            .permitAll();
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    auth.requestMatchers("/api/ai_ops", "/api/ai_ops/**").hasAnyRole("ADMIN", "OPS");
                    auth.requestMatchers("/api/**").authenticated();
                    auth.requestMatchers("/ws/**").hasAnyRole("ADMIN", "OPS");
                    auth.requestMatchers("/ops.html", "/ops.js").hasAnyRole("ADMIN", "OPS");
                    auth.requestMatchers("/admin.html", "/admin.js").hasRole("ADMIN");
                    auth.requestMatchers("/account.html", "/account.js").authenticated();
                    auth.requestMatchers("/", "/index.html", "/ops.html").authenticated();
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        new RestAuthenticationEntryPoint()
                ));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        configuration.setAllowCredentials(!properties.getAllowedOrigins().contains("*"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static final class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(
                HttpServletRequest request,
                HttpServletResponse response,
                AuthenticationException authenticationException
        ) throws IOException {
            String path = request.getRequestURI();
            if (path.startsWith("/api/") || path.startsWith("/ws/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"Unauthorized\",\"data\":null}");
                return;
            }
            response.sendRedirect("/login.html");
        }
    }
}
