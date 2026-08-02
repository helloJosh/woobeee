package com.woobeee.game.api.error;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameErrorCodeTest {

    /** surefire 는 모듈 디렉터리에서 돈다. */
    private static final Path FRONT_MESSAGES =
            Path.of("..", "front", "lib", "errors", "error-messages.ts");

    /** GAME-AC-26 */
    @Test
    void everyCodeIsDistinct() {
        List<String> codes = Arrays.stream(GameErrorCode.values()).map(GameErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    /** GAME-AC-26 — 코드는 접두사로 도메인을 드러낸다. */
    @Test
    void everyCodeIsNamespacedToTheGameDomain() {
        assertThat(Arrays.stream(GameErrorCode.values()).map(GameErrorCode::code))
                .allSatisfy(code -> assertThat(code).startsWith("game_"));
    }

    /**
     * GAME-AC-26 — 코드는 프론트와의 계약이다. {@code error-messages.ts} 에 없는 코드는
     * "예기치 못한 오류가 발생했습니다." 한 줄로 뭉개져 사용자에게 아무것도 알려주지 못한다.
     * 여기서 잡지 않으면 enum 에 값을 추가한 사람은 그 사실을 영영 모른다.
     *
     * <p>프론트 트리가 없는 환경(백엔드만 체크아웃)에서는 건너뛴다.
     */
    @Test
    void everyCodeHasAKoreanAndEnglishMessageInTheFrontMap() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);

        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        String korean = source.substring(koStart, enStart);
        String english = source.substring(enStart);

        for (GameErrorCode errorCode : GameErrorCode.values()) {
            String key = "\"" + errorCode.code() + "\":";
            assertThat(korean)
                    .as("Korean message for %s", errorCode.code())
                    .contains(key);
            assertThat(english)
                    .as("English message for %s", errorCode.code())
                    .contains(key);
        }
    }
}
