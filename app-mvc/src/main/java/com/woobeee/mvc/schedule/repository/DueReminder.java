package com.woobeee.mvc.schedule.repository;

import java.time.LocalTime;

/** {@link TaskReminderRepository#findDue} 의 한 행 — 발송에 필요한 것만 조인해 온다. */
public interface DueReminder {
    Long getReminderId();
    Integer getMinutesBefore();
    String getTaskName();
    /** 무소속 할 일이면 null. */
    String getProjectName();
    LocalTime getStartTime();
    String getWebhookUrl();
}
