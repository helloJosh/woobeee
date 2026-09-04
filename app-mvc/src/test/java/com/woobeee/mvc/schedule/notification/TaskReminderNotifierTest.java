package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.repository.DueReminder;
import com.woobeee.mvc.schedule.repository.TaskReminderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReminderNotifierTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 14, 0);

    @Mock TaskReminderRepository reminderRepository;
    @Mock SlackWebhookClient slackWebhookClient;

    @InjectMocks TaskReminderNotifier notifier;

    private static DueReminder due(long id, String webhook) {
        return new DueReminder() {
            public Long getReminderId() { return id; }
            public Integer getMinutesBefore() { return 30; }
            public String getTaskName() { return "회의"; }
            public String getProjectName() { return "DM"; }
            public LocalTime getStartTime() { return LocalTime.of(14, 30); }
            public String getWebhookUrl() { return webhook; }
        };
    }

    /** SCHEDULE-AC-36 — 보낸 건만 sent_at 이 찍힌다. */
    @Test
    void aSentReminderIsMarkedSent() {
        when(reminderRepository.findDue(NOW)).thenReturn(List.of(due(1L, "https://hooks.slack.com/services/a")));

        notifier.sendDueReminders(NOW);

        verify(slackWebhookClient).send("https://hooks.slack.com/services/a", "⏰ 30분 후 시작 (14:30) — [DM] 회의");
        verify(reminderRepository).markSent(1L, NOW);
    }

    /** SCHEDULE-AC-36 + AC-27 — 한 건의 실패는 sent_at 을 남기지 않고(다음 분 재시도) 나머지 발송을 막지 않는다. */
    @Test
    void aFailingSendIsNotMarkedAndDoesNotStopTheRest() {
        when(reminderRepository.findDue(NOW)).thenReturn(List.of(
                due(1L, "https://hooks.slack.com/services/broken"),
                due(2L, "https://hooks.slack.com/services/ok")));
        doThrow(new RuntimeException("slack down"))
                .when(slackWebhookClient).send(eq("https://hooks.slack.com/services/broken"), anyString());

        notifier.sendDueReminders(NOW);

        verify(reminderRepository, never()).markSent(eq(1L), eq(NOW));
        verify(reminderRepository).markSent(2L, NOW);
    }
}
