package com.woobeee.game.room;

public interface GameIdGenerator {
    String nextRoomId();

    String nextInviteCode();

    String nextGuestId();

    /** Plan 3의 장애물 생성 PRNG 시드. xorshift32는 0에서 멈추므로 0을 반환하면 안 된다. */
    int nextSeed();
}
