package com.woobeee.mvc.schedule.api.response;

/** {@code webhookUrl} 이 null 이면 알림 미사용. */
public record NotificationResponse(String webhookUrl) {
}
