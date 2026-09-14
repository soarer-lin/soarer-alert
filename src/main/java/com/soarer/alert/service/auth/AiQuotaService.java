package com.soarer.alert.service.auth;

import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.repository.AuthUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AiQuotaService {

    private static final int MAX_CUSTOM_QUOTA = 999999;

    private final AuthUserRepository userRepository;

    public AiQuotaService(AuthUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void consume(UUID userId) {
        if (userId == null) {
            return;
        }
        int updated = userRepository.consumeAiQuota(userId);
        if (updated == 0) {
            if (!userRepository.existsById(userId)) {
                throw new IllegalStateException("当前登录用户不存在");
            }
            throw new AiQuotaExhaustedException();
        }
    }

    @Transactional
    public void refund(UUID userId) {
        if (userId != null) {
            userRepository.refundAiQuota(userId);
        }
    }

    @Transactional
    public AuthUser setQuota(UUID userId, Integer quotaLimit) {
        if (quotaLimit != null && (quotaLimit < 1 || quotaLimit > MAX_CUSTOM_QUOTA)) {
            throw new IllegalArgumentException("次数必须是 1 到 999999 之间的正整数");
        }
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setAiQuotaLimit(quotaLimit);
        user.setAiQuotaUsed(0);
        return userRepository.save(user);
    }
}
