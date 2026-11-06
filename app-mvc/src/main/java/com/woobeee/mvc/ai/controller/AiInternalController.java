package com.woobeee.mvc.ai.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.ai.api.request.CompleteRequest;
import com.woobeee.mvc.ai.api.request.FailRequest;
import com.woobeee.mvc.ai.api.request.ProposeProductsRequest;
import com.woobeee.mvc.ai.api.request.ProposeTaskRequest;
import com.woobeee.mvc.ai.api.response.ClaimResponse;
import com.woobeee.mvc.ai.service.AiToolService;
import com.woobeee.mvc.ai.service.AiWorkerService;
import com.woobeee.mvc.pokemon.api.response.PokemonHostResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonManagedProductResponse;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final AiToolService toolService;

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

    /* ===== MCP 도구: 제안(기록만) ===== */

    @PostMapping("/requests/{id}/proposals/round-products")
    public ApiResponse<Void> proposeRoundProducts(@PathVariable UUID id, @RequestBody ProposeProductsRequest request) {
        toolService.proposeRoundProducts(id, request.attempt(), request.roundId(), request.items());
        return ApiResponse.success("Round products proposed");
    }

    @PostMapping("/requests/{id}/proposals/tasks")
    public ApiResponse<Void> proposeTask(@PathVariable UUID id, @RequestBody ProposeTaskRequest request) {
        toolService.proposeTask(id, request.attempt(), request.task());
        return ApiResponse.success("Task proposed");
    }

    /* ===== MCP 도구: 읽기 (요청자 범위) ===== */

    @GetMapping("/requests/{id}/pokemon/rounds")
    public ApiResponse<PokemonHostResponse> myRounds(@PathVariable UUID id, @RequestParam int attempt) {
        return ApiResponse.success(toolService.myRounds(id, attempt), "Rounds retrieved");
    }

    @GetMapping("/requests/{id}/pokemon/rounds/{roundId}/products")
    public ApiResponse<List<PokemonManagedProductResponse>> roundProducts(
            @PathVariable UUID id, @PathVariable Long roundId, @RequestParam int attempt) {
        return ApiResponse.success(toolService.roundProducts(id, attempt, roundId), "Round products retrieved");
    }

    @GetMapping("/requests/{id}/schedule/tree")
    public ApiResponse<GetScheduleTreeResponse> myScheduleTree(@PathVariable UUID id, @RequestParam int attempt) {
        return ApiResponse.success(toolService.myScheduleTree(id, attempt), "Schedule tree retrieved");
    }
}
