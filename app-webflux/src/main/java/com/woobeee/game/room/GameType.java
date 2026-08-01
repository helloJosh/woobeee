package com.woobeee.game.room;

public enum GameType {
    OMOK(2),
    DODGE(8);

    private final int capacity;

    GameType(int capacity) {
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }
}
