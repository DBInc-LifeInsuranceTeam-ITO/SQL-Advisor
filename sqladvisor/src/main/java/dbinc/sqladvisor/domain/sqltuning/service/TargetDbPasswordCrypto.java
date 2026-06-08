package dbinc.sqladvisor.domain.sqltuning.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TargetDbPasswordCrypto {

    private static final String PREFIX = "v1";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    public TargetDbPasswordCrypto(
            @Value("${sqladvisor.target-db.secret:${SQLADVISOR_TARGET_DB_SECRET:sqladvisor-target-db-secret}}") String secret
    ) {
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("DB password is required.");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return String.join(":",
                    PREFIX,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(encrypted)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("DB password encryption failed.", exception);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            throw new IllegalArgumentException("Stored DB password is empty.");
        }
        String[] parts = encryptedText.split(":", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("Stored DB password format is invalid.");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Stored DB password decryption failed.", exception);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
