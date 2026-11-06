package com.woobeee.mvc.ai.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.ai.api.response.AiRequestResponse;
import com.woobeee.mvc.ai.service.AiRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/back/ai")
@Tag(name = "AI Controller", description = "AI 요청하기 — 글·이미지를 올리면 상품·일정을 제안한다")
@RequiredArgsConstructor
public class AiController {
    private final AiRequestService aiRequestService;

    @PostMapping(value = "/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "AI 요청 접수", description = "글과 이미지(≤5장, 장당 ≤5MB)를 받는다. 진행 중인 요청이 있으면 409.")
    public ApiResponse<AiRequestResponse> create(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @RequestParam(name = "text", required = false) String text,
            @RequestParam(name = "images", required = false) List<MultipartFile> images) {
        return ApiResponse.createSuccess(aiRequestService.create(loginId, text, images), "AI request received");
    }

    @GetMapping("/requests/latest")
    @Operation(summary = "내 최근 AI 요청", description = "없으면 data 가 null. 새로고침한 화면이 진행 중인 요청을 다시 찾는다.")
    public ApiResponse<AiRequestResponse> latest(@RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(aiRequestService.latest(loginId), "Latest AI request retrieved");
    }

    @GetMapping("/requests/{id}")
    @Operation(summary = "AI 요청 조회", description = "상태와 제안 목록. 요청자 본인만.")
    public ApiResponse<AiRequestResponse> get(
            @RequestHeader(name = "loginId", required = false) String loginId, @PathVariable UUID id) {
        return ApiResponse.success(aiRequestService.get(loginId, id), "AI request retrieved");
    }

    @PostMapping("/requests/{id}/retry")
    @Operation(summary = "AI 요청 다시 시도", description = "DEAD 요청만. 본인 또는 전역 운영자.")
    public ApiResponse<AiRequestResponse> retry(
            @RequestHeader(name = "loginId", required = false) String loginId, @PathVariable UUID id) {
        return ApiResponse.success(aiRequestService.retry(loginId, id), "AI request queued again");
    }

    @PostMapping("/requests/{id}/actions/{actionId}/apply")
    @Operation(summary = "제안 승인", description = "제안 하나를 요청자 권한으로 실제 등록한다. 두 번 눌러도 한 번만 반영된다.")
    public ApiResponse<AiRequestResponse> apply(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable UUID id, @PathVariable Long actionId) {
        return ApiResponse.success(aiRequestService.apply(loginId, id, actionId), "AI proposal applied");
    }

    @PostMapping("/requests/{id}/actions/{actionId}/reject")
    @Operation(summary = "제안 거절", description = "제안 하나를 거절한다. 아무것도 등록하지 않는다.")
    public ApiResponse<AiRequestResponse> reject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable UUID id, @PathVariable Long actionId) {
        return ApiResponse.success(aiRequestService.reject(loginId, id, actionId), "AI proposal rejected");
    }
}
