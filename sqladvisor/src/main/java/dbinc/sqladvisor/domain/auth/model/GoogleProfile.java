package dbinc.sqladvisor.domain.auth.model;

public record GoogleProfile(
        String subject,
        String email,
        boolean emailVerified,
        String hostedDomain,
        String displayName,
        String pictureUrl,
        String locale
) {
}
