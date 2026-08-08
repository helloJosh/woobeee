package com.woobeee.game.api.error;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
     * GAME-AC-26 — 코드는 프론트와의 계약이고, 검사는 <b>양방향</b>이다.
     *
     * <p>enum → TS 한 방향만 보면 지도에 남은 죽은 키를 못 잡는다. 실제로 그런 일이 있었다:
     * 처음 추가한 21개 중 셋은 프론트에 절대 닿지 않는 코드였는데, 한 방향 검사는 그것을
     * 통과시켰다. 반대 방향(TS → enum)이 없으면 코드를 지우거나 이름을 바꿔도 문구만 조용히
     * 남는다.
     *
     * <p>키 매칭은 공백에 관대하다 — 포매터가 {@code "code" :} 로 정렬해도 깨지면 안 된다.
     *
     * <p>프론트 트리가 없는 환경(백엔드만 체크아웃)에서는 건너뛴다.
     */
    @Test
    void theFrontMapAndTheEnumAgreeInBothDirections() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);

        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        Set<String> declared = Arrays.stream(GameErrorCode.values())
                .map(GameErrorCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(gameKeysIn(source.substring(koStart, enStart)))
                .as("ko: keys must match GameErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(gameKeysIn(source.substring(enStart)))
                .as("en: keys must match GameErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    /** {@code "game_xxx"   :} 형태의 키를 뽑는다. 따옴표 안팎의 공백을 허용한다. */
    private static Set<String> gameKeysIn(String block) {
        Matcher matcher = Pattern.compile("\"\\s*(game_[A-Za-z0-9_]+)\\s*\"\\s*:").matcher(block);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
