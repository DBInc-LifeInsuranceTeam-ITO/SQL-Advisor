package dbinc.sqladvisor.domain.auth.dto;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record AuthConfigResponse(
            boolean authEnabled,
            boolean googleConfigured,
            String googleClientId,
            boolean localLoginEnabled
    ) {
    }

    public record GoogleLoginRequest(
            String credential,
            String nonce
    ) {
    }

    public record LocalLoginRequest(
            String identifier
    ) {
    }

    public record CurrentUserResponse(
            boolean authenticated,
            Long id,
            String email,
            String displayName,
            String pictureUrl,
            String role,
            List<String> authProviders
    ) {
        public static CurrentUserResponse anonymous() {
            return new CurrentUserResponse(false, null, null, null, null, null, List.of());
        }
    }
}
