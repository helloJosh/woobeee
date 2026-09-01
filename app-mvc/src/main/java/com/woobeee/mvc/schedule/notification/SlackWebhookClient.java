package com.woobeee.mvc.schedule.notification;

/** Slack Incoming Webhook 발송. 인터페이스로 둔 것은 발송기 테스트에서 HTTP 를 끊기 위해서다. */
public interface SlackWebhookClient {

    void send(String webhookUrl, String text);
}
