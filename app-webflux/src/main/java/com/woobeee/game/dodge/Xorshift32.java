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

    /** 32비트 부호 없는 값을 long 으로 넓혀 돌려준다. Java int 산술이 이미 32비트로 순환한다. */
    public int nextInt() {
        int x = state;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        state = x;
        return x;
    }

    public double nextDouble() {
        return Integer.toUnsignedLong(nextInt()) / TWO_POW_32;
    }
}
