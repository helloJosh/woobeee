package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.entity.Tasks;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 다이제스트 본문 조립 — 순수 함수라 DB·HTTP 없이 테스트한다.
 * 세 목록이 모두 비면 그날은 보낼 것이 없다 (SCHEDULE-AC-26).
 */
public final class SlackDigestFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM.dd");

    private SlackDigestFormatter() {
    }

    public static Optional<String> build(LocalDate today, List<Tasks> dueToday, List<Tasks> startingToday,
                                         List<Tasks> overdue, Map<Long, String> projectNames) {
        if (dueToday.isEmpty() && startingToday.isEmpty() && overdue.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📅 오늘의 일정 요약 (").append(today.format(DATE)).append(")");
        appendSection(sb, "⏰ 오늘 마감", dueToday, projectNames);
        appendSection(sb, "🟢 오늘 시작", startingToday, projectNames);
        appendSection(sb, "✅ 기한 경과 — 자동 완료 처리", overdue, projectNames);
        return Optional.of(sb.toString());
    }

    private static void appendSection(StringBuilder sb, String title, List<Tasks> tasks,
                                      Map<Long, String> projectNames) {
        if (tasks.isEmpty()) {
            return;
        }
        sb.append("\n\n").append(title).append(" (").append(tasks.size()).append(")");
        for (Tasks t : tasks) {
            sb.append("\n• [").append(projectNames.getOrDefault(t.getProjectId(), "?"))
                    .append("] ").append(t.getName());
        }
    }
}
