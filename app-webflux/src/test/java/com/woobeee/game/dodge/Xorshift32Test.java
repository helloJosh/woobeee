package com.woobeee.game.dodge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Xorshift32Test {

    /**
     * 골든 값이다. JS 포트(Plan 4)가 같은 수열을 내야 하므로 여기서 고정한다 —
     * 알고리즘을 건드리면 이 테스트가 먼저 깨져야 한다.
     *
     * <p>세 번째 값이 Integer.MAX_VALUE 를 넘으므로 부호 없는 값으로 비교한다.
     */
    @Test
    void producesTheDocumentedSequenceForSeedOne() {
        Xorshift32 random = new Xorshift32(1);

        assertThat(Integer.toUnsignedLong(random.nextInt())).isEqualTo(270369L);
        assertThat(Integer.toUnsignedLong(random.nextInt())).isEqualTo(67634689L);
        assertThat(Integer.toUnsignedLong(random.nextInt())).isEqualTo(2647435461L);
    }

    @Test
    void nextDoubleIsInsideTheUnitInterval() {
        Xorshift32 random = new Xorshift32(123456789);

        for (int i = 0; i < 10_000; i++) {
            double value = random.nextDouble();
            assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        }
    }

    @Test
    void theSameSeedGivesTheSameSequence() {
        Xorshift32 a = new Xorshift32(42);
        Xorshift32 b = new Xorshift32(42);

        for (int i = 0; i < 100; i++) {
            assertThat(a.nextInt()).isEqualTo(b.nextInt());
        }
    }

    @Test
    void differentSeedsDiverge() {
        Xorshift32 a = new Xorshift32(42);
        Xorshift32 b = new Xorshift32(43);

        assertThat(a.nextInt()).isNotEqualTo(b.nextInt());
    }

    /** xorshift 는 0 에서 멈춘다. 0 시드는 1 로 바꾼다. */
    @Test
    void zeroSeedIsRewrittenToOne() {
        Xorshift32 zero = new Xorshift32(0);
        Xorshift32 one = new Xorshift32(1);

        assertThat(zero.nextInt()).isEqualTo(one.nextInt());
    }

    @Test
    void neverGetsStuckAtZero() {
        Xorshift32 random = new Xorshift32(1);

        for (int i = 0; i < 1000; i++) {
            assertThat(random.nextInt()).isNotZero();
        }
    }
}
