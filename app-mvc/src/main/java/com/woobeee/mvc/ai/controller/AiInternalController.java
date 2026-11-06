package com.woobeee.mvc.ai.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.ai.api.request.CompleteRequest;
import com.woobeee.mvc.ai.api.request.FailRequest;
import com.woobeee.mvc.ai.api.response.ClaimResponse;
import com.woobeee.mvc.ai.service.AiWorkerService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 워커 전용. 사용자 인증이 아니라 {@code X-Ai-Worker-Secret}({@code AiWorkerSecretFilter})으로 막는다.
 * 프런트 rewrites 에 없는 경로라 브라우저에서는 닿지 않는다.
 */
@Hidden
@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
public class AiInternalController {
    private final AiWorkerService workerService;

    @PostMapping("/requests/{id}/claim")
    public ApiResponse<ClaimResponse> claim(@PathVariable UUID id) {
        return ApiResponse.success(workerService.claim(id), "AI request claimed");
    }

    @PostMapping("/requests/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable UUID id, @RequestBody CompleteRequest request) {
        workerService.complete(id, request.attempt(), request.summary());
        return ApiResponse.success("AI request completed");
    }

    @PostMapping("/requests/{id}/fail")
    public ApiResponse<Void> fail(@PathVariable UUID id, @RequestBody FailRequest request) {
        workerService.fail(id, request.attempt(), request.error());
        return ApiResponse.success("AI request failure recorded");
    }
}
