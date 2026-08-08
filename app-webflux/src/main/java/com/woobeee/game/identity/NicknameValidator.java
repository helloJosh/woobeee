package com.woobeee.game.identity;

import com.woobeee.game.api.error.GameErrorCode;
import org.springframework.web.server.ResponseStatusException;

public final class NicknameValidator {
    private static final int MAX_LENGTH = 20;

    private NicknameValidator() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw badRequest();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw badRequest();
        }

        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw badRequest();
            }
        }

        return trimmed;
    }

    private static ResponseStatusException badRequest() {
        return GameErrorCode.INVALID_NICKNAME.asException();
    }
}
