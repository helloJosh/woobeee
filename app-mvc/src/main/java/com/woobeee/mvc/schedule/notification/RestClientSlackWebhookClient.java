package com.woobeee.mvc.schedule.notification;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RestClientSlackWebhookClient implements SlackWebhookClient {

    private final RestClient restClient = RestClient.create();

    @Override
    public void send(String webhookUrl, String text) {
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
