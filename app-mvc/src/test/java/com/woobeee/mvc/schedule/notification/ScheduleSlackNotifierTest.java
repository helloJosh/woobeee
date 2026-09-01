package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleSlackNotifierTest {

    @Mock MemberRepository memberRepository;
    @Mock ScheduleDigestService digestService;
    @Mock SlackWebhookClient slackWebhookClient;

    @InjectMocks ScheduleSlackNotifier notifier;

    private Member memberWithWebhook(long id, String url) {
        Member member = Member.create("sub-" + id, "m" + id + "@example.com", "m" + id, true, true);
        ReflectionTestUtils.setField(member, "id", id);
        member.changeSlackWebhookUrl(url);
        return member;
    }

    /** SCHEDULE-AC-27 — 한 멤버의 발송 실패가 다음 멤버 발송을 막지 않는다. */
    @Test
    void aFailingWebhookDoesNotStopTheRemainingMembers() {
        Member first = memberWithWebhook(1L, "https://hooks.slack.com/services/first");
        Member second = memberWithWebhook(2L, "https://hooks.slack.com/services/second");
        when(memberRepository.findAllBySlackWebhookUrlNotNull()).thenReturn(List.of(first, second));
        when(digestService.buildAndSettleDigest(1L)).thenReturn(Optional.of("digest-1"));
        when(digestService.buildAndSettleDigest(2L)).thenReturn(Optional.of("digest-2"));
        doThrow(new RuntimeException("slack down"))
                .when(slackWebhookClient).send(first.getSlackWebhookUrl(), "digest-1");

        notifier.sendDailyDigests();

        verify(slackWebhookClient).send(second.getSlackWebhookUrl(), "digest-2");
    }

    /** SCHEDULE-AC-26 — 보낼 것이 없는 멤버에게는 발송하지 않는다. */
    @Test
    void anEmptyDigestIsNotSent() {
        Member member = memberWithWebhook(1L, "https://hooks.slack.com/services/quiet");
        when(memberRepository.findAllBySlackWebhookUrlNotNull()).thenReturn(List.of(member));
        when(digestService.buildAndSettleDigest(1L)).thenReturn(Optional.empty());

        notifier.sendDailyDigests();

        verify(slackWebhookClient, never()).send(anyString(), anyString());
    }
}
