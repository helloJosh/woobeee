package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.entity.Tasks;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SlackDigestFormatterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final Map<Long, String> NAMES = Map.of(10L, "DM", 20L, "POSCO");

    private Tasks task(long projectId, String name) {
        return Tasks.create(projectId, null, name, null, null, null, "#ef4444");
    }

    /** SCHEDULE-AC-26 — 세 목록이 모두 비면 보낼 것이 없다. */
    @Test
    void anEmptyDayProducesNoMessage() {
        Optional<String> digest = SlackDigestFormatter.build(
                TODAY, List.of(), List.of(), List.of(), NAMES);

        assertThat(digest).isEmpty();
    }

    /** SCHEDULE-AC-26 — 섹션별 개수와 [프로젝트명] 항목 형식. */
    @Test
    void sectionsCarryCountsAndProjectPrefixedLines() {
        String digest = SlackDigestFormatter.build(
                TODAY,
                List.of(task(10L, "SQL 피벗 전달"), task(20L, "TCP 진행상황 전달")),
                List.of(task(10L, "이미지 컴포넌트")),
                List.of(),
                NAMES).orElseThrow();

        assertThat(digest).contains("오늘의 일정 요약 (09.01)");
        assertThat(digest).contains("⏰ 오늘 마감 (2)");
        assertThat(digest).contains("• [DM] SQL 피벗 전달");
        assertThat(digest).contains("• [POSCO] TCP 진행상황 전달");
        assertThat(digest).contains("🟢 오늘 시작 (1)");
        assertThat(digest).contains("• [DM] 이미지 컴포넌트");
        // 빈 섹션은 제목도 나오지 않는다
        assertThat(digest).doesNotContain("기한 경과");
    }

    /** SCHEDULE-AC-26/28 — 기한 경과 섹션만 있어도 발송 대상이다. */
    @Test
    void overdueOnlyStillProducesAMessage() {
        String digest = SlackDigestFormatter.build(
                TODAY, List.of(), List.of(), List.of(task(10L, "밀린 일")), NAMES).orElseThrow();

        assertThat(digest).contains("✅ 기한 경과 — 자동 완료 처리 (1)");
        assertThat(digest).contains("• [DM] 밀린 일");
    }

    @Test
    void anUnknownProjectIdFallsBackToQuestionMark() {
        String digest = SlackDigestFormatter.build(
                TODAY, List.of(task(999L, "고아")), List.of(), List.of(), NAMES).orElseThrow();

        assertThat(digest).contains("• [?] 고아");
    }
}
