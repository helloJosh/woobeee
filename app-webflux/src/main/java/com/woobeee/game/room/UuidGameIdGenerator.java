package com.woobeee.game.room;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class UuidGameIdGenerator implements GameIdGenerator {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String nextRoomId() {
        return token(16);
    }

    @Override
    public String nextInviteCode() {
        return token(6);
    }

    @Override
    public String nextGuestId() {
        return token(12);
    }

    @Override
    public int nextSeed() {
        int seed = RANDOM.nextInt();
        return seed == 0 ? 1 : seed;
    }

    private String token(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
