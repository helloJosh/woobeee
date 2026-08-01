package com.woobeee.game.omok;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 오목 한 판의 상태. 순수 자바다 — 방·소켓·저장소를 모르며 OmokGameSink 만 이 클래스를 안다.
 */
public final class OmokGame {
    private final OmokBoard board = new OmokBoard();
    private final List<OmokMove> moves = new ArrayList<>();
    private final String blackParticipantId;
    private final String whiteParticipantId;
    private final Duration moveLimit;

    private Stone turn = Stone.BLACK;
    private Instant turnDeadline;
    private boolean finished;
    private String winnerParticipantId;

    public OmokGame(String blackParticipantId, String whiteParticipantId, Instant startedAt, Duration moveLimit) {
        this.blackParticipantId = blackParticipantId;
        this.whiteParticipantId = whiteParticipantId;
        this.moveLimit = moveLimit;
        this.turnDeadline = startedAt.plus(moveLimit);
    }

    public String blackParticipantId() {
        return blackParticipantId;
    }

    public String whiteParticipantId() {
        return whiteParticipantId;
    }

    public List<OmokMove> moves() {
        return List.copyOf(moves);
    }

    public boolean finished() {
        return finished;
    }

    public String winnerParticipantId() {
        return winnerParticipantId;
    }

    public Instant turnDeadline() {
        return turnDeadline;
    }

    public String currentTurnParticipantId() {
        return turn == Stone.BLACK ? blackParticipantId : whiteParticipantId;
    }

    public PlaceOutcome place(String participantId, int x, int y, Instant now) {
        if (finished) {
            return PlaceOutcome.rejected("GAME_FINISHED");
        }
        if (!currentTurnParticipantId().equals(participantId)) {
            return PlaceOutcome.rejected("NOT_YOUR_TURN");
        }
        if (!board.inBounds(x, y)) {
            return PlaceOutcome.rejected("OUT_OF_BOUNDS");
        }
        if (!board.isEmpty(x, y)) {
            return PlaceOutcome.rejected("OCCUPIED");
        }

        // 금수는 흑에게만 있다. 판정은 착수 전에 하고, 거절되면 판을 건드리지 않는다.
        if (turn == Stone.BLACK) {
            RenjuRule.Verdict verdict = RenjuRule.judge(board, x, y);
            if (verdict != RenjuRule.Verdict.LEGAL) {
                return PlaceOutcome.rejected(verdict.name());
            }
        }

        board.place(x, y, turn);
        moves.add(new OmokMove(moves.size() + 1, participantId, x, y, turn));

        if (WinRule.isWin(board, x, y, turn)) {
            finished = true;
            winnerParticipantId = participantId;
            return PlaceOutcome.win(turn, participantId, "FIVE_IN_A_ROW");
        }

        Stone placed = turn;
        turn = turn.opposite();
        turnDeadline = now.plus(moveLimit);
        return PlaceOutcome.placed(placed, turnDeadline);
    }

    public PlaceOutcome timeout(Instant now) {
        if (finished) {
            return PlaceOutcome.rejected("GAME_FINISHED");
        }
        if (now.isBefore(turnDeadline)) {
            return PlaceOutcome.rejected("NOT_EXPIRED");
        }

        String loser = currentTurnParticipantId();
        return finishWithWinner(opponentOf(loser), "TIMEOUT");
    }

    public PlaceOutcome resign(String participantId) {
        if (finished) {
            return PlaceOutcome.rejected("GAME_FINISHED");
        }
        return finishWithWinner(opponentOf(participantId), "RESIGN");
    }

    public String opponentOf(String participantId) {
        return blackParticipantId.equals(participantId) ? whiteParticipantId : blackParticipantId;
    }

    private PlaceOutcome finishWithWinner(String winner, String reason) {
        finished = true;
        winnerParticipantId = winner;
        return PlaceOutcome.win(null, winner, reason);
    }
}
