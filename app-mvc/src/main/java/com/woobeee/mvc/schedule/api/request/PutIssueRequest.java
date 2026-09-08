package com.woobeee.mvc.schedule.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 이슈 수정 — 내용과 해결 여부를 함께 교체한다 (SCHEDULE-AC-40). */
public record PutIssueRequest(
        @NotBlank @Size(max = 1000) String content,
        boolean resolved
) {}
