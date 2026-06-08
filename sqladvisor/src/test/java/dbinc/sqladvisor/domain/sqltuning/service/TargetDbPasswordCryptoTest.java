package dbinc.sqladvisor.domain.sqltuning.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetDbPasswordCryptoTest {

    @Test
    void encryptsAndDecryptsPassword() {
        TargetDbPasswordCrypto crypto = new TargetDbPasswordCrypto("test-secret");

        String encrypted = crypto.encrypt("oracle-password");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("oracle-password");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("oracle-password");
    }
}
