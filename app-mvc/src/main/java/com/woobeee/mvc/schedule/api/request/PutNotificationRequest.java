package com.woobeee.mvc.schedule.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PutNotificationRequest(
        @NotBlank @Size(max = 500) String webhookUrl
) {}
