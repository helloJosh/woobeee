package com.woobeee.mvc.ai.exception;

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

/** PokemonErrorCodeTest 와 같은 계약 — 코드 키는 서버 enum 과 프런트 지도가 정확히 일치해야 한다. */
class AiErrorCodeTest {

    private static final Path FRONT_MESSAGES = Path.of("..", "front", "lib", "errors", "error-messages.ts");

    @Test
    void everyCodeIsDistinctAndNamespacedToTheAiDomain() {
        List<String> codes = Arrays.stream(AiErrorCode.values()).map(AiErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).startsWith("ai_"));
    }

    @Test
    void theFrontMapAndTheEnumAgreeInBothDirections() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);
        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        Set<String> declared = Arrays.stream(AiErrorCode.values()).map(AiErrorCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(aiKeysIn(source.substring(koStart, enStart))).as("ko")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(aiKeysIn(source.substring(enStart))).as("en")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Set<String> aiKeysIn(String block) {
        Matcher matcher = Pattern.compile("\"\\s*(ai_[A-Za-z0-9_]+)\\s*\"\\s*:").matcher(block);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
