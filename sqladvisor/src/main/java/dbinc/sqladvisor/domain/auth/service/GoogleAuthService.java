package dbinc.sqladvisor.domain.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import dbinc.sqladvisor.domain.auth.model.GoogleProfile;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GoogleAuthService {

    @Value("${app.auth.google.client-id:}")
    private String googleClientId;

    @Value("${app.auth.google.allowed-domains:}")
    private String allowedDomainsValue;

    private GoogleIdTokenVerifier verifier;
    private Set<String> allowedDomains;

    @PostConstruct
    public void init() {
        allowedDomains = splitCsv(allowedDomainsValue);
        if (hasText(googleClientId)) {
            verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(List.of(googleClientId.trim()))
                    .build();
        }
    }

    public boolean isConfigured() {
        return hasText(googleClientId);
    }

    public String clientId() {
        return googleClientId == null ? "" : googleClientId.trim();
    }

    public GoogleProfile verify(String credential) {
        if (!hasText(googleClientId) || verifier == null) {
            throw new IllegalArgumentException("Google Client ID가 설정되어 있지 않습니다.");
        }
        if (!hasText(credential)) {
            throw new IllegalArgumentException("Google credential이 비어 있습니다.");
        }

        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(credential.trim());
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("Google ID Token 검증에 실패했습니다.", exception);
        }

        if (idToken == null) {
            throw new IllegalArgumentException("유효하지 않은 Google ID Token입니다.");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        Boolean emailVerified = payload.getEmailVerified();
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new IllegalArgumentException("Google 이메일 검증이 완료된 계정만 사용할 수 있습니다.");
        }

        String hostedDomain = payload.getHostedDomain();
        if (!allowedDomains.isEmpty() && !allowedDomains.contains(normalize(hostedDomain))) {
            throw new IllegalArgumentException("허용되지 않은 Google Workspace 도메인입니다.");
        }

        return new GoogleProfile(
                payload.getSubject(),
                payload.getEmail(),
                Boolean.TRUE.equals(emailVerified),
                hostedDomain,
                stringClaim(payload, "name"),
                stringClaim(payload, "picture"),
                stringClaim(payload, "locale")
        );
    }

    private String stringClaim(GoogleIdToken.Payload payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Set<String> splitCsv(String value) {
        if (!hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(this::normalize)
                .filter(this::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
