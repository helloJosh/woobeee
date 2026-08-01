package com.woobeee.game.omok;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OmokGameTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LIMIT = Duration.ofSeconds(60);
    private static final String BLACK = "m:11";
    private static final String WHITE = "g:a";

    private OmokGame game;

    @BeforeEach
    void setUp() {
        game = new OmokGame(BLACK, WHITE, START, LIMIT);
    }

    @Test
    void blackMovesFirst() {
        assertThat(game.currentTurnParticipantId()).isEqualTo(BLACK);
        assertThat(game.turnDeadline()).isEqualTo(START.plus(LIMIT));
    }

    /** GAME-AC-14 */
    @Test
    void outOfTurnPlacementIsRejected() {
        PlaceOutcome outcome = game.place(WHITE, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo("NOT_YOUR_TURN");
        assertThat(game.moves()).isEmpty();
    }

    /** GAME-AC-14 */
    @Test
    void occupiedCellIsRejected() {
        game.place(BLACK, 7, 7, START);

        PlaceOutcome outcome = game.place(WHITE, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo("OCCUPIED");
    }

    @Test
    void offBoardPlacementIsRejected() {
        PlaceOutcome outcome = game.place(BLACK, 15, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo("OUT_OF_BOUNDS");
    }

    @Test
    void placingAdvancesTheTurnAndExtendsTheDeadline() {
        Instant firstMoveAt = START.plusSeconds(20);
        PlaceOutcome outcome = game.place(BLACK, 7, 7, firstMoveAt);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.PLACED);
        assertThat(outcome.stone()).isEqualTo(Stone.BLACK);
        assertThat(game.currentTurnParticipantId()).isEqualTo(WHITE);
        // now(=firstMoveAt) 기준으로 재계산된 값이어야 한다 — START 기준 값과는 다르다.
        assertThat(outcome.turnDeadline()).isEqualTo(firstMoveAt.plus(LIMIT));
        assertThat(game.turnDeadline()).isEqualTo(firstMoveAt.plus(LIMIT));

        // 두 번째 수도 그 자신의(더 늦은) now 기준으로 다시 연장되는지 본다 — 한 번만이 아니라
        // 매 수마다 갱신됨을 증명한다.
        Instant secondMoveAt = firstMoveAt.plusSeconds(15);
        PlaceOutcome secondOutcome = game.place(WHITE, 8, 8, secondMoveAt);

        assertThat(secondOutcome.status()).isEqualTo(PlaceOutcome.Status.PLACED);
        assertThat(secondOutcome.turnDeadline()).isEqualTo(secondMoveAt.plus(LIMIT));
        assertThat(game.turnDeadline()).isEqualTo(secondMoveAt.plus(LIMIT));
    }

    /** GAME-AC-10 — 금수는 거절되고 판이 바뀌지 않는다 */
    @Test
    void aForbiddenBlackMoveIsRejectedAndLeavesTheBoardUnchanged() {
        // 흑 삼삼을 만든다: 가로 (5,7)(6,7), 세로 (7,5)(7,6)
        game.place(BLACK, 5, 7, START);
        game.place(WHITE, 0, 0, START);
        game.place(BLACK, 6, 7, START);
        game.place(WHITE, 0, 1, START);
        game.place(BLACK, 7, 5, START);
        game.place(WHITE, 0, 2, START);
        game.place(BLACK, 7, 6, START);
        game.place(WHITE, 0, 3, START);

        int movesBefore = game.moves().size();
        PlaceOutcome outcome = game.place(BLACK, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo("DOUBLE_THREE");
        assertThat(game.moves()).hasSize(movesBefore);
        assertThat(game.currentTurnParticipantId()).isEqualTo(BLACK);
    }

    /**
     * 색깔 가드가 실제로 하는 일을 증명하는 테스트다. 흑이 (5,7)(6,7)(7,5)(7,6) 에 실제로
     * 돌을 놓아 (7,7) 을 완성점으로 하는 삼삼 모양을 만든다 — 이는
     * aForbiddenBlackMoveIsRejectedAndLeavesTheBoardUnchanged 에서 흑이 그 자리에 두면
     * 거절됨을 이미 확인한 바로 그 모양이다. 다만 이번엔 그 자리를 두는 사람이 백이다.
     *
     * <p>{@code place} 의 {@code turn == Stone.BLACK} 가드를 지우면 {@code RenjuRule.judge}
     * 가 백의 차례에도 호출되고, judge 는 누가 두는지와 무관하게 항상 흑돌을 가정하고 판을
     * 검사하므로 실제로 판에 있는 이 흑 삼삼을 발견해 DOUBLE_THREE 로 (부당하게) 거절한다.
     * 가드가 있어야만 이 테스트가 PLACED 로 통과한다.
     */
    @Test
    void whiteMayPlayShapesThatWouldBeForbiddenForBlack() {
        game.place(BLACK, 5, 7, START);
        game.place(WHITE, 0, 0, START);
        game.place(BLACK, 6, 7, START);
        game.place(WHITE, 0, 1, START);
        game.place(BLACK, 7, 5, START);
        game.place(WHITE, 0, 2, START);
        game.place(BLACK, 7, 6, START);

        PlaceOutcome outcome = game.place(WHITE, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.PLACED);
    }

    @Test
    void fiveInARowWinsAndFinishesTheGame() {
        for (int i = 0; i < 4; i++) {
            game.place(BLACK, 3 + i, 7, START);
            game.place(WHITE, 3 + i, 9, START);
        }

        PlaceOutcome outcome = game.place(BLACK, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.WIN);
        assertThat(outcome.winnerParticipantId()).isEqualTo(BLACK);
        assertThat(game.finished()).isTrue();
    }

    @Test
    void movesAreRecordedInOrder() {
        game.place(BLACK, 7, 7, START);
        game.place(WHITE, 8, 8, START);

        assertThat(game.moves()).hasSize(2);
        assertThat(game.moves().get(0).index()).isEqualTo(1);
        assertThat(game.moves().get(0).participantId()).isEqualTo(BLACK);
        assertThat(game.moves().get(1).index()).isEqualTo(2);
        assertThat(game.moves().get(1).stone()).isEqualTo(Stone.WHITE);
    }

    @Test
    void placingAfterTheGameEndsIsRejected() {
        for (int i = 0; i < 4; i++) {
            game.place(BLACK, 3 + i, 7, START);
            game.place(WHITE, 3 + i, 9, START);
        }
        game.place(BLACK, 7, 7, START);

        PlaceOutcome outcome = game.place(WHITE, 0, 0, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo("GAME_FINISHED");
    }

    /** GAME-AC-15 */
    @Test
    void exceedingTheMoveLimitLosesTheGame() {
        PlaceOutcome outcome = game.timeout(START.plusSeconds(61));

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.WIN);
        assertThat(outcome.winnerParticipantId()).isEqualTo(WHITE);
        assertThat(outcome.reason()).isEqualTo("TIMEOUT");
        assertThat(game.finished()).isTrue();
    }

    /** GAME-AC-15 */
    @Test
    void timeoutBeforeTheDeadlineDoesNothing() {
        PlaceOutcome outcome = game.timeout(START.plusSeconds(59));

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.REJECTED);
        assertThat(game.finished()).isFalse();
    }

    @Test
    void resignFinishesTheGameForTheOpponent() {
        PlaceOutcome outcome = game.resign(BLACK);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.WIN);
        assertThat(outcome.winnerParticipantId()).isEqualTo(WHITE);
        assertThat(game.finished()).isTrue();
    }
}
