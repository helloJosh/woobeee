package com.woobeee.mvc.ai.api.request;

import com.woobeee.mvc.schedule.api.request.PostTaskRequest;

public record ProposeTaskRequest(int attempt, PostTaskRequest task) {
}
