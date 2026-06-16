package dbinc.sqladvisor.domain.auth.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public record AppUserPrincipal(
        Long id,
        String email,
        String displayName,
        String pictureUrl,
        String role,
        boolean enabled
) implements Serializable {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_MONITOR = "MONITOR";

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + normalizedRole()));
    }

    public String normalizedRole() {
        return role == null || role.isBlank() ? ROLE_USER : role.trim().toUpperCase();
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(normalizedRole());
    }

    public boolean isUser() {
        return ROLE_USER.equals(normalizedRole());
    }

    public boolean isMonitor() {
        return ROLE_MONITOR.equals(normalizedRole());
    }

    /**
     * 조회 권한
     * ADMIN, USER, MONITOR 모두 가능
     */
    public boolean canRead() {
        return isAdmin() || isUser() || isMonitor();
    }

    /**
     * 쓰기/수정/삭제/튜닝 실행 권한
     * MONITOR는 제외
     */
    public boolean canWrite() {
        return isAdmin() || isUser();
    }
}