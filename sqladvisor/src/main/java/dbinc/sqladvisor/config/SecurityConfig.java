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

    private static final String[] READ_ROLES = {"ADMIN", "USER", "MONITOR"};
    private static final String[] WRITE_ROLES = {"ADMIN", "USER"};

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

                    // 정적 리소스 / 프론트 라우트
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

                    // 인증 API



                    .requestMatchers("/api/auth/**").permitAll()

                    // 내부 워커 API - 기존 정책 유지
                    .requestMatchers("/api/internal/worker/**").permitAll()

                    // Actuator 공개
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                    // AI 설정은 ADMIN만 허용
                    .requestMatchers(HttpMethod.GET, "/api/config/ai").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/config/ai/models").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/config/ai").hasRole("ADMIN")

                    // ===== MONITOR 허용: 조회성 API =====

                    // AWR 리포트 조회
                    .requestMatchers(HttpMethod.GET, "/api/reports").hasAnyRole(READ_ROLES)
                    .requestMatchers(HttpMethod.GET, "/api/reports/**").hasAnyRole(READ_ROLES)
                    .requestMatchers(HttpMethod.GET, "/api/analysis/**").hasAnyRole(READ_ROLES)

                    // SQL 튜닝 이력 / 결과 조회
                    .requestMatchers(HttpMethod.GET, "/api/sql-tuning").hasAnyRole(READ_ROLES)
                    .requestMatchers(HttpMethod.GET, "/api/sql-tuning/**").hasAnyRole(READ_ROLES)

                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/config/ai").hasRole("ADMIN")

                    // Target DB 접속 목록 조회
                    .requestMatchers(HttpMethod.GET, "/api/db-connections").hasAnyRole(READ_ROLES)

                    // ===== MONITOR 차단: 등록/수정/삭제/실행성 API =====

                    // AWR 업로드, 분석 실행, 채팅, SQL 튜닝 실행
                    .requestMatchers(HttpMethod.POST, "/api/reports").hasAnyRole(WRITE_ROLES)
                    .requestMatchers(HttpMethod.POST, "/api/reports/**").hasAnyRole(WRITE_ROLES)

                    // SQL 튜닝 실행, Direct DB context 수집, Direct DB tuning, 질문 등록
                    .requestMatchers(HttpMethod.POST, "/api/sql-tuning").hasAnyRole(WRITE_ROLES)
                    .requestMatchers(HttpMethod.POST, "/api/sql-tuning/**").hasAnyRole(WRITE_ROLES)

                    // DB 접속정보 생성/테스트/수정/삭제
                    .requestMatchers(HttpMethod.POST, "/api/db-connections").hasAnyRole(WRITE_ROLES)
                    .requestMatchers(HttpMethod.POST, "/api/db-connections/**").hasAnyRole(WRITE_ROLES)
                    .requestMatchers(HttpMethod.PATCH, "/api/db-connections/**").hasAnyRole(WRITE_ROLES)
                    .requestMatchers(HttpMethod.DELETE, "/api/db-connections/**").hasAnyRole(WRITE_ROLES)

                    // 그 외는 로그인 필요
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