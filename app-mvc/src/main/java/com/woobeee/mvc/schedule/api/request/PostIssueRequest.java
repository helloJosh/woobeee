package com.woobeee.mvc.schedule.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 할 일 밑 이슈 생성 — 새 이슈는 항상 미해결 (SCHEDULE-AC-40). */
public record PostIssueRequest(
        @NotBlank @Size(max = 1000) String content
) {}
