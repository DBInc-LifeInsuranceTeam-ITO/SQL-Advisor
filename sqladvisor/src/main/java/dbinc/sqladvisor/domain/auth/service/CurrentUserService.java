package dbinc.sqladvisor.domain.auth.service;

import dbinc.sqladvisor.domain.auth.model.AppUserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    public AppUserPrincipal requireUser() {
        return currentUser()
                .orElseThrow(() -> new AccessDeniedException("로그인이 필요합니다."));
    }

    public java.util.Optional<AppUserPrincipal> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return java.util.Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal appUserPrincipal) {
            return java.util.Optional.of(appUserPrincipal);
        }
        return java.util.Optional.empty();
    }

    public Long currentUserIdOrNull() {
        return currentUser().map(AppUserPrincipal::id).orElse(null);
    }

    public boolean isCurrentUserAdmin() {
        return currentUser()
                .map(AppUserPrincipal::isAdmin)
                .orElse(false);
    }
}
