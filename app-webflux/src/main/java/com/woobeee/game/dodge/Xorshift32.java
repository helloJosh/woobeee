package com.woobeee.game.dodge;

/**
 * 기보 재생용 PRNG. 명세가 xorshift32 로 고정했다.
 *
 * <p>{@code java.util.Random} 을 쓰지 않는 이유는 브라우저가 같은 수열을 만들어야 하기 때문이다.
 * JS 는 각 시프트 뒤에 {@code | 0}, 마지막에 {@code >>> 0} 을 붙이면 같은 결과가 나온다.
 */
public final class Xorshift32 {
    private static final double TWO_POW_32 = 4294967296.0;

    private int state;

    public Xorshift32(int seed) {
        this.state = seed == 0 ? 1 : seed;
    }

    public int state() {
        return state;
    }

    /**
     * 다음 32비트 상태를 그대로 돌려준다 — <b>부호 있는</b> {@code int} 다. Java 의 int 산술이
     * 이미 32비트로 순환하므로 마스킹이 따로 필요 없다. JS 포트에서는 각 좌시프트 뒤에
     * {@code | 0} 을 붙여 같은 32비트 순환을 만들어야 한다.
     */
    public int nextInt() {
        int x = state;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        state = x;
        return x;
    }

    /**
     * {@link #nextInt()} 의 32비트 값을 <b>부호 없는</b> long 으로 넓힌 뒤 2^32 로 나눠
     * {@code [0, 1)} 로 옮긴다. JS 포트는 반환값에 {@code >>> 0} 을 붙여 같은 부호 없는 확장을
     * 만든 뒤 같은 상수로 나눈다.
     */
    public double nextDouble() {
        return Integer.toUnsignedLong(nextInt()) / TWO_POW_32;
    }
}
