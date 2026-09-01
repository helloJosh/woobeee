package com.woobeee.mvc._common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 일정 Slack 다이제스트({@code ScheduleSlackNotifier}) 같은 @Scheduled 잡을 켠다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
