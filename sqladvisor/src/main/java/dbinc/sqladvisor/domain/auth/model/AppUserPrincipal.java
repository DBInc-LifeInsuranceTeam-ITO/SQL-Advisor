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

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + normalizedRole()));
    }

    public String normalizedRole() {
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }

    public boolean isAdmin() {
        return "ADMIN".equals(normalizedRole());
    }
}
