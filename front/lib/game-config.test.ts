import { afterEach, describe, expect, it } from "vitest";
import { gameEnabled, gameSocketUrl } from "@/lib/game-config";

const ORIGINAL = { ...process.env };

afterEach(() => {
  process.env = { ...ORIGINAL };
});

describe("gameEnabled", () => {
  it("기본은 꺼짐이다 — app-webflux 를 띄우지 않으므로 붙지 않는다", () => {
    delete process.env.NEXT_PUBLIC_GAME_ENABLED;
    expect(gameEnabled()).toBe(false);
  });

  it('"true" 일 때만 켜진다', () => {
    process.env.NEXT_PUBLIC_GAME_ENABLED = "true";
    expect(gameEnabled()).toBe(true);
  });

  // "1"·"yes"·"TRUE" 를 켜짐으로 읽으면, 오타 하나가 죽은 서버로 붙는 빌드를 만든다.
  // 켜는 쪽만 정확히 한 문자열로 좁힌다.
  it.each(["false", "1", "yes", "TRUE", " true", ""])(
    "%o 는 꺼짐이다",
    (value) => {
      process.env.NEXT_PUBLIC_GAME_ENABLED = value;
      expect(gameEnabled()).toBe(false);
    },
  );
});

describe("gameSocketUrl", () => {
  it("설정된 오리진에 /ws/game 을 붙인다", () => {
    process.env.NEXT_PUBLIC_WS_BASE_URL = "wss://game.example.com";
    expect(gameSocketUrl()).toBe("wss://game.example.com/ws/game");
  });

  it("끝의 슬래시를 겹치지 않는다", () => {
    process.env.NEXT_PUBLIC_WS_BASE_URL = "wss://game.example.com/";
    expect(gameSocketUrl()).toBe("wss://game.example.com/ws/game");
  });
});
