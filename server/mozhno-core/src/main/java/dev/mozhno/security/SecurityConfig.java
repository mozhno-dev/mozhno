package dev.mozhno.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import dev.mozhno.spi.AuthenticationProviderSpi;
import dev.mozhno.spi.impl.DelegatingAuthenticationFilter;
import dev.mozhno.auth.AuthProperties;
import dev.mozhno.auth.UserRepository;
import dev.mozhno.logging.LoggingMdcFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final List<AuthenticationProviderSpi> authProviders;
    private final RateLimitProperties rateLimitProperties;
    private final SecurityProperties securityProperties;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    public SecurityConfig(List<AuthenticationProviderSpi> authProviders,
                          RateLimitProperties rateLimitProperties,
                          SecurityProperties securityProperties,
                          UserRepository userRepository,
                          AuthProperties authProperties) {
        this.authProviders = authProviders;
        this.rateLimitProperties = rateLimitProperties;
        this.securityProperties = securityProperties;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(securityProperties.getBcryptStrength());
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN").implies("DEVELOPER")
            .role("DEVELOPER").implies("VIEWER")
            .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        SecurityProperties.Cors corsProps = securityProperties.getCors();
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = corsProps.getAllowedOrigins() == null ? List.of()
            : corsProps.getAllowedOrigins().stream()
                .filter(o -> o != null && !o.isBlank())
                .map(String::trim)
                .toList();
        if (!allowedOrigins.isEmpty()) {
            config.setAllowedOrigins(allowedOrigins);
            config.setAllowCredentials(true);
        }
        config.setAllowedMethods(corsProps.getAllowedMethods());
        config.setAllowedHeaders(corsProps.getAllowedHeaders());
        config.setExposedHeaders(corsProps.getExposedHeaders());
        config.setMaxAge(corsProps.getMaxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    @Order(0)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(securityProperties.getHeaders().getHstsMaxAgeSeconds()))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(securityProperties.getHeaders().getContentSecurityPolicy()))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Authentication required\",\"code\":\"UNAUTHORIZED\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Access denied\",\"code\":\"FORBIDDEN\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/client/features").hasRole("SERVER")
                .requestMatchers(HttpMethod.POST, "/api/client/evaluate").hasRole("CLIENT")
                .requestMatchers(HttpMethod.POST, "/api/client/metrics").hasRole("CLIENT")
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/refresh").permitAll()
                .requestMatchers("/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/auth/forgot-password").permitAll()
                .requestMatchers("/api/v1/auth/reset-password").permitAll()
                .requestMatchers("/api/v1/auth/accept-invite").permitAll()
                .requestMatchers("/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/projects/logo").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/avatar").authenticated()
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "DEVELOPER", "VIEWER")
                .requestMatchers("/index.html", "/assets/**", "/favicon*", "/logo*.svg", "/manifest.json").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().denyAll()
            )
            .addFilterBefore(new RateLimitFilter(rateLimitProperties.isEnabled(), rateLimitProperties), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new LoggingMdcFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new DelegatingAuthenticationFilter(authProviders), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new UserActivityFilter(userRepository, authProperties), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(spaForwardFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
        ".js", ".css", ".html", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".eot", ".map", ".json", ".xml", ".txt", ".webp"
    );

    @Bean
    public OncePerRequestFilter spaForwardFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                if (isApiPath(path) || isStaticResource(path)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                request.getRequestDispatcher("/index.html").forward(request, response);
            }

            private boolean isApiPath(String path) {
                return path.startsWith("/api") || path.startsWith("/v3/api-docs")
                    || path.startsWith("/swagger-ui") || path.startsWith("/actuator");
            }

            private boolean isStaticResource(String path) {
                int dot = path.lastIndexOf('.');
                if (dot > 0) {
                    String ext = path.substring(dot).toLowerCase();
                    return STATIC_EXTENSIONS.contains(ext);
                }
                return false;
            }
        };
    }
}
