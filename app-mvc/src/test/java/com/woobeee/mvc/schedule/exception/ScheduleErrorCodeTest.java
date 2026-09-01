package com.woobeee.mvc.schedule.exception;

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

class ScheduleErrorCodeTest {

    /** surefire 는 모듈 디렉터리에서 돈다. */
    private static final Path FRONT_MESSAGES =
            Path.of("..", "front", "lib", "errors", "error-messages.ts");

    /** SCHEDULE-AC-15 */
    @Test
    void everyCodeIsDistinct() {
        List<String> codes = Arrays.stream(ScheduleErrorCode.values())
                .map(ScheduleErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    /** SCHEDULE-AC-15 — 코드는 접두사로 도메인을 드러낸다. */
    @Test
    void everyCodeIsNamespacedToTheScheduleDomain() {
        assertThat(Arrays.stream(ScheduleErrorCode.values()).map(ScheduleErrorCode::code))
                .allSatisfy(code -> assertThat(code).startsWith("schedule_"));
    }

    /** SCHEDULE-AC-15 — enum ↔ TS 지도 양방향 대조. 한 방향만 보면 죽은 키를 못 잡는다. */
    @Test
    void theFrontMapAndTheEnumAgreeInBothDirections() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);

        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        Set<String> declared = Arrays.stream(ScheduleErrorCode.values())
                .map(ScheduleErrorCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(scheduleKeysIn(source.substring(koStart, enStart)))
                .as("ko: keys must match ScheduleErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(scheduleKeysIn(source.substring(enStart)))
                .as("en: keys must match ScheduleErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Set<String> scheduleKeysIn(String block) {
        Matcher matcher = Pattern.compile("\"\\s*(schedule_[A-Za-z0-9_]+)\\s*\"\\s*:").matcher(block);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
