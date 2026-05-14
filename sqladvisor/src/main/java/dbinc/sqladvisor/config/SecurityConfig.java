package dbinc.sqladvisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.common.enums.ErrorCode;
import dbinc.sqladvisor.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        if (authEnabled) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(
                            "/",
                            "/index.html",
                            "/favicon.ico",
                            "/assets/**",
                            "/fonts/**",
                            "/login",
                            "/dashboard",
                            "/upload",
                            "/reports/**",
                            "/chat",
                            "/error"
                    ).permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/internal/worker/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/config/ai").hasRole("ADMIN")
                    .anyRequest().authenticated()
            );
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        http.exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) ->
                        writeError(response, objectMapper, ErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeError(response, objectMapper, ErrorCode.FORBIDDEN))
        );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response,
                            ObjectMapper objectMapper,
                            ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }
}
