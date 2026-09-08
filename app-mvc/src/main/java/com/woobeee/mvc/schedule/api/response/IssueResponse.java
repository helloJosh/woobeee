package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.TaskIssues;

/** 이슈 한 건 — 단건 응답과 트리 응답의 TaskNode.issues 가 같은 모양을 쓴다 (프론트 ScheduleIssue). */
public record IssueResponse(Long id, Long taskId, String content, boolean resolved) {
    public static IssueResponse from(TaskIssues i) {
        return new IssueResponse(i.getId(), i.getTaskId(), i.getContent(), i.isResolved());
    }
}
