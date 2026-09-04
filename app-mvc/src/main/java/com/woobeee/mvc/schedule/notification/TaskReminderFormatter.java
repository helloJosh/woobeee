package com.woobeee.mvc.schedule.notification;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 시작 전 알림 본문 — 순수 함수라 DB·HTTP 없이 테스트한다 (SCHEDULE-AC-36). */
public final class TaskReminderFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private TaskReminderFormatter() {
    }

    /** {@code projectName} 이 null 이면 무소속 — 프로젝트 접두 없이 (SCHEDULE-AC-31 과 동일). */
    public static String build(int minutesBefore, LocalTime startTime, String projectName, String taskName) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏰ ").append(minutesBefore).append("분 후 시작 (").append(startTime.format(TIME)).append(") — ");
        if (projectName != null) {
            sb.append('[').append(projectName).append("] ");
        }
        sb.append(taskName);
        return sb.toString();
    }
}
