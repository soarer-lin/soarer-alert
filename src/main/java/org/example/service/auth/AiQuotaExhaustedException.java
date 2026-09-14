package org.example.service.auth;

public class AiQuotaExhaustedException extends RuntimeException {

    public static final String ERROR_CODE = "AI_QUOTA_EXHAUSTED";
    public static final String MESSAGE = "额度已耗尽，请联系管理员重置~";

    public AiQuotaExhaustedException() {
        super(MESSAGE);
    }
}
