package com.woobeee.game.room;

public enum GameType {
    OMOK(2, 2),
    // 장애물피하기는 혼자서도 시작할 수 있다(연습 모드). DodgeGame 의 틱 종료 규칙이
    // 1인 게임 예외(participantIds.size() > 1)를 이미 갖추고 있어 게임이 성립한다.
    DODGE(8, 1);

    private final int capacity;
    private final int minPlayersToStart;

    GameType(int capacity, int minPlayersToStart) {
        this.capacity = capacity;
        this.minPlayersToStart = minPlayersToStart;
    }

    public int capacity() {
        return capacity;
    }

    public int minPlayersToStart() {
        return minPlayersToStart;
    }
}
