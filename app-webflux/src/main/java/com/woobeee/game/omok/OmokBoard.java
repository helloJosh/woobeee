package com.woobeee.game.omok;

/**
 * 15x15 오목판. 순수 자바다 — Spring, Reactor, I/O 어느 것에도 의존하지 않는다.
 * 규칙 테스트가 프레임워크 없이 돌아가는 것이 이 클래스의 존재 이유다.
 */
public final class OmokBoard {
    public static final int SIZE = 15;

    private final Stone[][] cells;

    public OmokBoard() {
        this.cells = new Stone[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                cells[y][x] = Stone.EMPTY;
            }
        }
    }

    private OmokBoard(Stone[][] cells) {
        this.cells = cells;
    }

    /** 테스트용 ASCII 파서. '.' 빈칸, 'X' 흑, 'O' 백. 행은 y=0 부터 위에서 아래로. */
    public static OmokBoard of(String... rows) {
        if (rows.length != SIZE) {
            throw new IllegalArgumentException("Board needs exactly " + SIZE + " rows, got " + rows.length);
        }

        OmokBoard board = new OmokBoard();
        for (int y = 0; y < SIZE; y++) {
            String row = rows[y];
            if (row.length() != SIZE) {
                throw new IllegalArgumentException("Row " + y + " needs exactly " + SIZE + " columns");
            }
            for (int x = 0; x < SIZE; x++) {
                board.cells[y][x] = switch (row.charAt(x)) {
                    case 'X' -> Stone.BLACK;
                    case 'O' -> Stone.WHITE;
                    case '.' -> Stone.EMPTY;
                    default -> throw new IllegalArgumentException("Unexpected char at " + x + "," + y);
                };
            }
        }
        return board;
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    /** 판 밖은 EMPTY 가 아니라 "벽"이지만, 규칙 판정은 항상 inBounds 를 먼저 본다. */
    public Stone at(int x, int y) {
        return inBounds(x, y) ? cells[y][x] : Stone.EMPTY;
    }

    public boolean isEmpty(int x, int y) {
        return inBounds(x, y) && cells[y][x] == Stone.EMPTY;
    }

    public void place(int x, int y, Stone stone) {
        cells[y][x] = stone;
    }

    public void clear(int x, int y) {
        cells[y][x] = Stone.EMPTY;
    }

    public OmokBoard copy() {
        Stone[][] copied = new Stone[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            System.arraycopy(cells[y], 0, copied[y], 0, SIZE);
        }
        return new OmokBoard(copied);
    }
}
