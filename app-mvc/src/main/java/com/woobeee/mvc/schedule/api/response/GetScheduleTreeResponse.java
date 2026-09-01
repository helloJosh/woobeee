// 프론트 lib/schedule.ts 의 타입과 1:1 이다.
package com.woobeee.mvc.schedule.api.response;

import java.time.LocalDate;
import java.util.List;

public record GetScheduleTreeResponse(List<ProjectNode> projects) {

    public record ProjectNode(
            Long id, String name, String status, LocalDate startDate, LocalDate endDate,
            List<MilestoneNode> milestones, List<TaskNode> tasks
    ) {}

    public record MilestoneNode(
            Long id, String name, String status, LocalDate startDate, LocalDate endDate,
            List<MilestoneNode> milestones, List<TaskNode> tasks
    ) {}

    public record TaskNode(
            Long id, Long milestoneId, String name, String status,
            LocalDate startDate, LocalDate endDate, String color
    ) {}
}
