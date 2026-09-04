package com.woobeee.mvc.schedule.notification;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TaskReminderFormatterTest {

    /** SCHEDULE-AC-36 — 프로젝트 소속 할 일은 접두가 붙는다. */
    @Test
    void aProjectTaskCarriesItsProjectPrefix() {
        assertThat(TaskReminderFormatter.build(30, LocalTime.of(14, 30), "DM", "회의"))
                .isEqualTo("⏰ 30분 후 시작 (14:30) — [DM] 회의");
    }

    /** SCHEDULE-AC-36 + AC-31 — 무소속은 접두 없이. */
    @Test
    void aStandaloneTaskHasNoPrefix() {
        assertThat(TaskReminderFormatter.build(10, LocalTime.of(9, 5), null, "장보기"))
                .isEqualTo("⏰ 10분 후 시작 (09:05) — 장보기");
    }
}
