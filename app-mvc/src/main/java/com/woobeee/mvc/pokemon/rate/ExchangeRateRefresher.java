package com.woobeee.mvc.pokemon.rate;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 환율을 매일 한 번 갱신한다. 공동구매의 원화는 이 값으로 계산한 "예상 금액"이다. */
@Component
@RequiredArgsConstructor
public class ExchangeRateRefresher {
    private final ExchangeRateService exchangeRateService;

    @Scheduled(cron = "0 10 9 * * *", zone = "Asia/Seoul")
    public void refresh() {
        exchangeRateService.refreshAll();
    }
}
