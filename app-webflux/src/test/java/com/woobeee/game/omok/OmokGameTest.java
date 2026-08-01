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
        PlaceOutcome outcome = game.place(BLACK, 7, 7, START);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.PLACED);
        assertThat(outcome.stone()).isEqualTo(Stone.BLACK);
        assertThat(game.currentTurnParticipantId()).isEqualTo(WHITE);
        assertThat(outcome.turnDeadline()).isEqualTo(START.plus(LIMIT));
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

    @Test
    void whiteMayPlayShapesThatWouldBeForbiddenForBlack() {
        // 흑의 메꿈수는 서로 떨어뜨려 둔다: 붙여 놓으면 (0,0)..(0,4) 가 그대로 흑의 5목이 되어
        // 백의 차례가 오기 전에 흑이 이겨버린다.
        game.place(BLACK, 0, 0, START);
        game.place(WHITE, 5, 7, START);
        game.place(BLACK, 0, 2, START);
        game.place(WHITE, 6, 7, START);
        game.place(BLACK, 0, 4, START);
        game.place(WHITE, 7, 5, START);
        game.place(BLACK, 0, 6, START);
        game.place(WHITE, 7, 6, START);
        game.place(BLACK, 0, 8, START);

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
