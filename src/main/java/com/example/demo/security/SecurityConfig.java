package com.example.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
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

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter  jwtFilter;

    /**
     * cors.allowed-origins — production da aniq domenni kiriting.
     * Wildcard (*) faqat dev uchun.
     * Misol: cors.allowed-origins=https://example.uz,https://admin.example.uz
     */
    @Value("${cors.allowed-origins:http://localhost:8080}")
    private String allowedOriginsRaw;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          JwtAuthenticationFilter jwtFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtFilter          = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // Static fayllar
                        .requestMatchers(
                                "/", "/*.html", "/*.css", "/*.js", "/*.ico",
                                "/*.png", "/*.jpg", "/*.svg",
                                "/static/**", "/assets/**"
                        ).permitAll()

                        // Login, refresh, setup-status — ochiq
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/setup-status"
                        ).permitAll()

                        // Setup — ochiq, lekin SetupState orqali bir martadan himoyalangan
                        .requestMatchers("/api/auth/setup").permitAll()

                        // Register — faqat SUPER_ADMIN
                        .requestMatchers("/api/auth/register")
                        .hasRole("SUPER_ADMIN")

                        // /me — autentifikatsiya kerak
                        .requestMatchers("/api/auth/me")
                        .hasAnyRole("SUPER_ADMIN", "ADMIN", "OPERATOR")

                        // Faqat SUPER_ADMIN
                        .requestMatchers("/api/admin/users/**")
                        .hasRole("SUPER_ADMIN")

                        // Barcha adminlar
                        .requestMatchers(
                                "/api/admin/applications/**",
                                "/api/admin/stats/**",
                                "/api/admin/profile/**",
                                "/api/admin/chats/**",
                                "/api/admin/files/**",
                                "/api/admin/search-history/**",
                                "/api/admin/categories/**"
                        ).hasAnyRole("SUPER_ADMIN", "ADMIN", "OPERATOR")

                        .requestMatchers("/api/admin/**")
                        .hasAnyRole("SUPER_ADMIN", "ADMIN", "OPERATOR")

                        // Actuator
                        .requestMatchers("/actuator/health").permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .authenticationProvider(authenticationProvider());

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Wildcard (*) + allowCredentials(true) birga ishlamaydi — Pattern ishlatiladi
        if (origins.contains("*")) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}