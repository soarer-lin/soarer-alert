package com.soarer.alert.service.auth;

import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.repository.AuthUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuotaServiceTest {

    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private AiQuotaService service;

    @BeforeEach
    void setUp() {
        service = new AiQuotaService(userRepository);
    }

    @Test
    void consumeUsesAtomicRepositoryUpdate() {
        UUID userId = UUID.randomUUID();
        when(userRepository.consumeAiQuota(userId)).thenReturn(1);

        service.consume(userId);

        verify(userRepository).consumeAiQuota(userId);
    }

    @Test
    void consumeRejectsWhenQuotaIsExhausted() {
        UUID userId = UUID.randomUUID();
        when(userRepository.consumeAiQuota(userId)).thenReturn(0);
        when(userRepository.existsById(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.consume(userId))
                .isInstanceOf(AiQuotaExhaustedException.class)
                .hasMessage(AiQuotaExhaustedException.MESSAGE);
    }

    @Test
    void setQuotaResetsUsageAndSupportsUnlimited() {
        UUID userId = UUID.randomUUID();
        AuthUser user = new AuthUser();
        user.setId(userId);
        user.setAiQuotaLimit(10);
        user.setAiQuotaUsed(10);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUser result = service.setQuota(userId, null);

        assertThat(result.getAiQuotaLimit()).isNull();
        assertThat(result.getAiQuotaUsed()).isZero();
    }

    @Test
    void setQuotaRejectsInvalidCustomValue() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.setQuota(userId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("次数必须是 1 到 999999 之间的正整数");
    }
}
