package dbinc.sqladvisor.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.auth.model.AppUserPrincipal;
import dbinc.sqladvisor.domain.auth.model.GoogleProfile;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AuthRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthRepository(@Qualifier("primaryDataSource") DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    id BIGSERIAL PRIMARY KEY,
                    email TEXT UNIQUE NOT NULL,
                    display_name TEXT,
                    picture_url TEXT,
                    role TEXT NOT NULL DEFAULT 'USER',
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    last_login_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS auth_identity (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                    provider TEXT NOT NULL,
                    provider_user_id TEXT NOT NULL,
                    email TEXT,
                    email_verified BOOLEAN DEFAULT false,
                    password_hash TEXT,
                    provider_metadata JSONB DEFAULT '{}'::jsonb,
                    last_login_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(provider, provider_user_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS auth_login_audit (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
                    provider TEXT,
                    username TEXT,
                    success BOOLEAN NOT NULL,
                    failure_reason TEXT,
                    ip_address TEXT,
                    user_agent TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_auth_identity_user ON auth_identity(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_auth_login_audit_user ON auth_login_audit(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_auth_login_audit_created ON auth_login_audit(created_at DESC)");
    }

    @Transactional
    public AppUserPrincipal upsertGoogleUser(GoogleProfile profile, Set<String> adminEmails) {
        Optional<AppUserPrincipal> identityUser = findUserByIdentity("google", profile.subject());
        Long userId = identityUser
                .map(AppUserPrincipal::id)
                .orElseGet(() -> findUserByEmail(profile.email())
                        .map(AppUserPrincipal::id)
                        .orElseGet(() -> createUser(profile, adminEmails.contains(normalize(profile.email())))));

        boolean admin = adminEmails.contains(normalize(profile.email()));
        jdbcTemplate.update("""
                        UPDATE app_user
                           SET email = ?,
                               display_name = ?,
                               picture_url = ?,
                               role = CASE WHEN ? THEN 'ADMIN' ELSE role END,
                               enabled = true,
                               last_login_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """,
                profile.email(),
                profile.displayName(),
                profile.pictureUrl(),
                admin,
                userId
        );

        jdbcTemplate.update("""
                        INSERT INTO auth_identity(
                            user_id, provider, provider_user_id, email, email_verified,
                            provider_metadata, last_login_at, updated_at
                        )
                        VALUES (?, 'google', ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (provider, provider_user_id)
                        DO UPDATE SET user_id = EXCLUDED.user_id,
                                      email = EXCLUDED.email,
                                      email_verified = EXCLUDED.email_verified,
                                      provider_metadata = EXCLUDED.provider_metadata,
                                      last_login_at = CURRENT_TIMESTAMP,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                userId,
                profile.subject(),
                profile.email(),
                profile.emailVerified(),
                toJson(Map.of(
                        "hostedDomain", nullToEmpty(profile.hostedDomain()),
                        "displayName", nullToEmpty(profile.displayName()),
                        "pictureUrl", nullToEmpty(profile.pictureUrl()),
                        "locale", nullToEmpty(profile.locale())
                ))
        );

        return findUserById(userId)
                .orElseThrow(() -> new IllegalStateException("로그인 사용자를 다시 조회하지 못했습니다."));
    }

    public Optional<AppUserPrincipal> findUserById(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, email, display_name, picture_url, role, enabled
                          FROM app_user
                         WHERE id = ?
                        """,
                userMapper(),
                userId
        ).stream().findFirst();
    }

    public Optional<AppUserPrincipal> findUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, email, display_name, picture_url, role, enabled
                          FROM app_user
                         WHERE lower(email) = lower(?)
                        """,
                userMapper(),
                email
        ).stream().findFirst();
    }

    public Optional<AppUserPrincipal> findUserByIdentity(String provider, String providerUserId) {
        if (provider == null || providerUserId == null || provider.isBlank() || providerUserId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT u.id, u.email, u.display_name, u.picture_url, u.role, u.enabled
                          FROM auth_identity i
                          JOIN app_user u ON u.id = i.user_id
                         WHERE i.provider = ?
                           AND i.provider_user_id = ?
                        """,
                userMapper(),
                provider,
                providerUserId
        ).stream().findFirst();
    }

    public List<String> findProviders(long userId) {
        return jdbcTemplate.query("""
                        SELECT provider
                          FROM auth_identity
                         WHERE user_id = ?
                         ORDER BY provider
                        """,
                (rs, rowNum) -> rs.getString("provider"),
                userId
        );
    }

    @Transactional
    public AppUserPrincipal upsertLocalUser(String identifier, String email, String displayName, Set<String> adminEmails) {
        Optional<AppUserPrincipal> identityUser = findUserByIdentity("local", identifier);
        Long userId = identityUser
                .map(AppUserPrincipal::id)
                .orElseGet(() -> findUserByEmail(email)
                        .map(AppUserPrincipal::id)
                        .orElseGet(() -> createLocalUser(email, displayName, adminEmails.contains(normalize(email)))));

        boolean admin = adminEmails.contains(normalize(email));
        jdbcTemplate.update("""
                        UPDATE app_user
                           SET email = ?,
                               display_name = ?,
                               role = CASE WHEN ? THEN 'ADMIN' ELSE role END,
                               enabled = true,
                               last_login_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """,
                email,
                displayName,
                admin,
                userId
        );

        jdbcTemplate.update("""
                        INSERT INTO auth_identity(
                            user_id, provider, provider_user_id, email, email_verified,
                            provider_metadata, last_login_at, updated_at
                        )
                        VALUES (?, 'local', ?, ?, true, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (provider, provider_user_id)
                        DO UPDATE SET user_id = EXCLUDED.user_id,
                                      email = EXCLUDED.email,
                                      email_verified = true,
                                      provider_metadata = EXCLUDED.provider_metadata,
                                      last_login_at = CURRENT_TIMESTAMP,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                userId,
                identifier,
                email,
                toJson(Map.of("displayName", displayName))
        );

        return findUserById(userId)
                .orElseThrow(() -> new IllegalStateException("로그인 사용자를 다시 조회하지 못했습니다."));
    }

    @Transactional
    public AppUserPrincipal upsertInternalUser(String identifier, String email, String displayName, Set<String> adminEmails) {
        Optional<AppUserPrincipal> identityUser = findUserByIdentity("internal-ad", identifier);
        Long userId = identityUser
                .map(AppUserPrincipal::id)
                .orElseGet(() -> findUserByEmail(email)
                        .map(AppUserPrincipal::id)
                        .orElseGet(() -> createLocalUser(email, displayName, adminEmails.contains(normalize(email)))));

        boolean admin = adminEmails.contains(normalize(email));
        jdbcTemplate.update("""
                        UPDATE app_user
                           SET email = ?,
                               display_name = ?,
                               role = CASE WHEN ? THEN 'ADMIN' ELSE role END,
                               enabled = true,
                               last_login_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """,
                email,
                displayName,
                admin,
                userId
        );

        jdbcTemplate.update("""
                        INSERT INTO auth_identity(
                            user_id, provider, provider_user_id, email, email_verified,
                            provider_metadata, last_login_at, updated_at
                        )
                        VALUES (?, 'internal-ad', ?, ?, true, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (provider, provider_user_id)
                        DO UPDATE SET user_id = EXCLUDED.user_id,
                                      email = EXCLUDED.email,
                                      email_verified = true,
                                      provider_metadata = EXCLUDED.provider_metadata,
                                      last_login_at = CURRENT_TIMESTAMP,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                userId,
                identifier,
                email,
                toJson(Map.of(
                        "displayName", displayName,
                        "loginEno", identifier
                ))
        );

        return findUserById(userId)
                .orElseThrow(() -> new IllegalStateException("Login user could not be reloaded."));
    }

    public void recordLoginAttempt(Long userId, String provider, String username, boolean success,
                                   String failureReason, String ipAddress, String userAgent) {
        jdbcTemplate.update("""
                        INSERT INTO auth_login_audit(
                            user_id, provider, username, success, failure_reason, ip_address, user_agent
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                userId,
                provider,
                username,
                success,
                failureReason,
                ipAddress,
                userAgent
        );
    }

    private Long createUser(GoogleProfile profile, boolean admin) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO app_user(
                                email, display_name, picture_url, role, enabled,
                                last_login_at, updated_at
                            )
                            VALUES (?, ?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """, new String[]{"id"});
            statement.setString(1, profile.email());
            statement.setString(2, profile.displayName());
            statement.setString(3, profile.pictureUrl());
            statement.setString(4, admin ? "ADMIN" : "USER");
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long createLocalUser(String email, String displayName, boolean admin) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO app_user(
                                email, display_name, role, enabled,
                                last_login_at, updated_at
                            )
                            VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """, new String[]{"id"});
            statement.setString(1, email);
            statement.setString(2, displayName);
            statement.setString(3, admin ? "ADMIN" : "USER");
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private RowMapper<AppUserPrincipal> userMapper() {
        return (rs, rowNum) -> new AppUserPrincipal(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("picture_url"),
                rs.getString("role"),
                rs.getBoolean("enabled")
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("인증 메타데이터를 JSON으로 저장하지 못했습니다.", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public List<AppUserPrincipal> findAllUsers() {
        return jdbcTemplate.query("""
                    SELECT id, email, display_name, picture_url, role, enabled
                      FROM app_user
                     ORDER BY email
                    """,
                userMapper()
        );
    }

    public AppUserPrincipal updateUserRole(Long userId, String role) {
        int updated = jdbcTemplate.update("""
                    UPDATE app_user
                       SET role = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """,
                role,
                userId
        );
        if (updated == 0) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        return findUserById(userId)
                .orElseThrow(() -> new IllegalStateException("수정한 사용자를 다시 조회하지 못했습니다."));
    }
}
