package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.repository.DueReminder;
import com.woobeee.mvc.schedule.repository.TaskReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 매 분 시작 전 알림을 Slack 으로 보낸다 (SCHEDULE-AC-36).
 * 다이제스트({@link ScheduleSlackNotifier})와 같은 전제 — 단일 인스턴스, 한 건의 실패가 나머지를 막지 않는다.
 * 기준 시각은 다이제스트 cron 과 같은 Asia/Seoul.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskReminderNotifier {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TaskReminderRepository reminderRepository;
    private final SlackWebhookClient slackWebhookClient;

    @Scheduled(fixedDelay = 60_000)
    public void sendDueReminders() {
        sendDueReminders(LocalDateTime.now(Clock.system(ZONE)));
    }

    /** 성공한 건만 sent_at 을 찍는다 — 실패한 건은 다음 분에 다시 시도되고, 시작 시각을 넘기면 조회에서 빠진다. */
    void sendDueReminders(LocalDateTime now) {
        for (DueReminder r : reminderRepository.findDue(now)) {
            try {
                slackWebhookClient.send(r.getWebhookUrl(), TaskReminderFormatter.build(
                        r.getMinutesBefore(), r.getStartTime(), r.getProjectName(), r.getTaskName()));
                reminderRepository.markSent(r.getReminderId(), now);
            } catch (Exception ex) {
                log.warn("task reminder {} failed", r.getReminderId(), ex);
            }
        }
    }
}
