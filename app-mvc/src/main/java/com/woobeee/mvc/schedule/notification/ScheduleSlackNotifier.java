package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 아침 일정 다이제스트를 Slack 으로 보낸다 (SCHEDULE-AC-26).
 * 단일 인스턴스 전제 — 인스턴스가 늘어나면 중복 발송 방지를 따로 설계해야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleSlackNotifier {

    private final MemberRepository memberRepository;
    private final ScheduleDigestService digestService;
    private final SlackWebhookClient slackWebhookClient;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void sendDailyDigests() {
        for (Member member : memberRepository.findAllBySlackWebhookUrlNotNull()) {
            try {
                digestService.buildAndSettleDigest(member.getId())
                        .ifPresent(text -> slackWebhookClient.send(member.getSlackWebhookUrl(), text));
            } catch (Exception ex) {
                // SCHEDULE-AC-27 — 한 멤버의 실패가 나머지 발송을 막지 않는다
                log.warn("slack digest failed for member {}", member.getId(), ex);
            }
        }
    }
}
