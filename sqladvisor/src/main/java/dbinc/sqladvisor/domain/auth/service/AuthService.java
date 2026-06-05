package dbinc.sqladvisor.domain.auth.service;

import dbinc.sqladvisor.domain.auth.dto.AuthDtos;
import dbinc.sqladvisor.domain.auth.model.AppUserPrincipal;
import dbinc.sqladvisor.domain.auth.model.GoogleProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthRepository authRepository;
    private final GoogleAuthService googleAuthService;

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    @Value("${app.auth.mode:external}")
    private String authMode;

    @Value("${app.auth.local-login-enabled:false}")
    private boolean localLoginEnabled;

    @Value("${app.auth.internal.email-domain:internal.local}")
    private String internalEmailDomain;

    @Value("${app.auth.admin-emails:}")
    private String adminEmailsValue;

    public AuthDtos.AuthConfigResponse config() {
        String mode = normalizedAuthMode();
        return new AuthDtos.AuthConfigResponse(
                authEnabled,
                mode,
                googleLoginEnabled() && googleAuthService.isConfigured(),
                googleAuthService.clientId(),
                internalLoginEnabled(),
                localLoginEnabled
        );
    }

    public AppUserPrincipal authenticateGoogle(String credential, String nonce, HttpServletRequest request) {
        String username = "";
        try {
            if (!googleLoginEnabled()) {
                throw new IllegalArgumentException("Google login is disabled for this auth mode.");
            }
            GoogleProfile profile = googleAuthService.verify(credential, nonce);
            username = profile.email();
            AppUserPrincipal principal = authRepository.upsertGoogleUser(profile, adminEmails());
            if (!principal.enabled()) {
                authRepository.recordLoginAttempt(principal.id(), "google", username, false,
                        "disabled", clientIp(request), userAgent(request));
                throw new IllegalArgumentException("비활성화된 사용자입니다.");
            }
            authRepository.recordLoginAttempt(principal.id(), "google", username, true,
                    null, clientIp(request), userAgent(request));
            return principal;
        } catch (RuntimeException exception) {
            authRepository.recordLoginAttempt(null, "google", username, false,
                    exception.getMessage(), clientIp(request), userAgent(request));
            throw exception;
        }
    }

    public AppUserPrincipal authenticateLocal(String identifier, HttpServletRequest request) {
        String username = normalize(identifier);
        try {
            if (!localLoginEnabled) {
                throw new IllegalArgumentException("로컬 로그인이 비활성화되어 있습니다.");
            }
            if (username.isBlank()) {
                throw new IllegalArgumentException("ID 또는 이메일을 입력하세요.");
            }

            String email = resolveLocalEmail(username);
            String displayName = displayName(email, username);
            AppUserPrincipal principal = authRepository.upsertLocalUser(username, email, displayName, adminEmails());
            if (!principal.enabled()) {
                authRepository.recordLoginAttempt(principal.id(), "local", username, false,
                        "disabled", clientIp(request), userAgent(request));
                throw new IllegalArgumentException("비활성화된 사용자입니다.");
            }
            authRepository.recordLoginAttempt(principal.id(), "local", username, true,
                    null, clientIp(request), userAgent(request));
            return principal;
        } catch (RuntimeException exception) {
            authRepository.recordLoginAttempt(null, "local", username, false,
                    exception.getMessage(), clientIp(request), userAgent(request));
            throw exception;
        }
    }

    public AppUserPrincipal authenticateInternal(String identifier, HttpServletRequest request) {
        String username = normalize(firstNonBlank(
                identifier,
                request.getParameter("loginEno"),
                request.getHeader("X-Login-Eno"),
                sessionLoginEno(request)
        ));
        try {
            if (!internalLoginEnabled()) {
                throw new IllegalArgumentException("Internal AD login is disabled for this auth mode.");
            }
            if (username.isBlank()) {
                throw new IllegalArgumentException("AD account identifier is required.");
            }

            String email = resolveInternalEmail(username);
            String displayName = displayName(email, internalAccountName(username));
            AppUserPrincipal principal = authRepository.upsertInternalUser(username, email, displayName, adminEmails());
            if (!principal.enabled()) {
                authRepository.recordLoginAttempt(principal.id(), "internal-ad", username, false,
                        "disabled", clientIp(request), userAgent(request));
                throw new IllegalArgumentException("User is disabled.");
            }
            request.getSession(true).setAttribute("loginEno", username);
            authRepository.recordLoginAttempt(principal.id(), "internal-ad", username, true,
                    null, clientIp(request), userAgent(request));
            return principal;
        } catch (RuntimeException exception) {
            authRepository.recordLoginAttempt(null, "internal-ad", username, false,
                    exception.getMessage(), clientIp(request), userAgent(request));
            throw exception;
        }
    }

    public AuthDtos.CurrentUserResponse currentUserResponse(AppUserPrincipal principal) {
        if (principal == null) {
            return AuthDtos.CurrentUserResponse.anonymous();
        }
        List<String> providers = authRepository.findProviders(principal.id());
        return new AuthDtos.CurrentUserResponse(
                true,
                principal.id(),
                principal.email(),
                principal.displayName(),
                principal.pictureUrl(),
                principal.normalizedRole(),
                providers
        );
    }

    private boolean googleLoginEnabled() {
        return authEnabled && "external".equals(normalizedAuthMode());
    }

    private boolean internalLoginEnabled() {
        return authEnabled && "internal".equals(normalizedAuthMode());
    }

    private String normalizedAuthMode() {
        String mode = normalize(authMode);
        return "internal".equals(mode) ? "internal" : "external";
    }

    private Set<String> adminEmails() {
        if (adminEmailsValue == null || adminEmailsValue.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(adminEmailsValue.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String resolveInternalEmail(String identifier) {
        if (identifier.contains("@")) {
            return identifier;
        }
        return internalAccountName(identifier) + "@" + normalizedInternalEmailDomain();
    }

    private String internalAccountName(String identifier) {
        int slashIndex = identifier.lastIndexOf('\\');
        return slashIndex >= 0 ? identifier.substring(slashIndex + 1) : identifier;
    }

    private String normalizedInternalEmailDomain() {
        String domain = normalize(internalEmailDomain);
        return domain.isBlank() ? "internal.local" : domain;
    }

    private String sessionLoginEno(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "";
        }
        Object value = session.getAttribute("loginEno");
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String resolveLocalEmail(String identifier) {
        Set<String> adminEmails = adminEmails();
        if (identifier.contains("@") && adminEmails.contains(identifier)) {
            return identifier;
        }
        return adminEmails.stream()
                .filter(email -> email.contains("@"))
                .filter(email -> localPart(email).equals(identifier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("허용된 로컬 로그인 ID가 아닙니다."));
    }

    private String localPart(String email) {
        int atIndex = email.indexOf("@");
        return atIndex > 0 ? email.substring(0, atIndex) : email;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String displayName(String email, String identifier) {
        String value = email == null || email.isBlank() ? identifier : email;
        int atIndex = value.indexOf("@");
        return atIndex > 0 ? value.substring(0, atIndex) : value;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return userAgent == null ? "" : userAgent;
    }
}
