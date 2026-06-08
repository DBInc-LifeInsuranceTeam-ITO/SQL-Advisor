package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetDbConnectionServiceTest {

    @Test
    void createsConnectionForNonAdminUser() {
        TargetDbConnectionRepository repository = mock(TargetDbConnectionRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        TargetDbConnectionService service = new TargetDbConnectionService(
                repository,
                new TargetDbPasswordCrypto("test-secret"),
                currentUserService
        );
        TargetDbConnectionRepository.TargetDbConnectionRecord saved = record(10L, 7L, "PRIVATE");

        when(currentUserService.currentUserIdOrNull()).thenReturn(7L);
        when(repository.save(eq(7L), eq("DEV readonly"), eq("ORACLE"), eq("jdbc:oracle:thin:@//db:1521/ORCLPDB1"),
                eq("SQLADVISOR_RO"), anyString(), eq("PRIVATE"), eq(false), eq(600))).thenReturn(saved);

        SqlTuningDtos.TargetDbConnectionResponse response = service.createConnection(request("secret"));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("DEV readonly");
    }

    @Test
    void updatesConnectionForOwner() {
        TargetDbConnectionRepository repository = mock(TargetDbConnectionRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        TargetDbConnectionService service = new TargetDbConnectionService(
                repository,
                new TargetDbPasswordCrypto("test-secret"),
                currentUserService
        );
        TargetDbConnectionRepository.TargetDbConnectionRecord current = record(10L, 7L, "PRIVATE");

        when(currentUserService.currentUserIdOrNull()).thenReturn(7L);
        when(currentUserService.isCurrentUserAdmin()).thenReturn(false);
        when(repository.findVisibleById(10L, 7L, false)).thenReturn(Optional.of(current));
        when(repository.update(eq(10L), eq("DEV readonly"), eq("ORACLE"), anyString(), eq("SQLADVISOR_RO"),
                anyString(), eq("PRIVATE"), anyBoolean(), anyInt())).thenReturn(current);

        SqlTuningDtos.TargetDbConnectionResponse response = service.updateConnection(10L, request(""));

        assertThat(response.id()).isEqualTo(10L);
        verify(repository).update(eq(10L), eq("DEV readonly"), eq("ORACLE"), anyString(), eq("SQLADVISOR_RO"),
                anyString(), eq("PRIVATE"), eq(false), eq(600));
    }

    @Test
    void blocksUpdateForVisibleConnectionOwnedByAnotherUser() {
        TargetDbConnectionRepository repository = mock(TargetDbConnectionRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        TargetDbConnectionService service = new TargetDbConnectionService(
                repository,
                new TargetDbPasswordCrypto("test-secret"),
                currentUserService
        );
        TargetDbConnectionRepository.TargetDbConnectionRecord shared = record(10L, 9L, "SHARED");

        when(currentUserService.currentUserIdOrNull()).thenReturn(7L);
        when(currentUserService.isCurrentUserAdmin()).thenReturn(false);
        when(repository.findVisibleById(10L, 7L, false)).thenReturn(Optional.of(shared));

        assertThatThrownBy(() -> service.updateConnection(10L, request("")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("owner or admin");
        verify(repository, never()).update(eq(10L), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), anyInt());
    }

    private SqlTuningDtos.TargetDbConnectionRequest request(String password) {
        return new SqlTuningDtos.TargetDbConnectionRequest(
                "DEV readonly",
                "ORACLE",
                "jdbc:oracle:thin:@//db:1521/ORCLPDB1",
                "SQLADVISOR_RO",
                password,
                "PRIVATE",
                false,
                600
        );
    }

    private TargetDbConnectionRepository.TargetDbConnectionRecord record(long id, long ownerUserId, String visibility) {
        LocalDateTime now = LocalDateTime.now();
        return new TargetDbConnectionRepository.TargetDbConnectionRecord(
                id,
                ownerUserId,
                "DEV readonly",
                "ORACLE",
                "jdbc:oracle:thin:@//db:1521/ORCLPDB1",
                "SQLADVISOR_RO",
                new TargetDbPasswordCrypto("test-secret").encrypt("stored-secret"),
                visibility,
                false,
                600,
                now,
                now
        );
    }
}
