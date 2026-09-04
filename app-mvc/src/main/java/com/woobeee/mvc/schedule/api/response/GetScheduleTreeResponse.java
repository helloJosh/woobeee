// 프론트 lib/schedule.ts 의 타입과 1:1 이다.
package com.woobeee.mvc.schedule.api.response;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** {@code tasks} 는 어느 프로젝트에도 속하지 않은 무소속 할 일 (SCHEDULE-AC-31). */
public record GetScheduleTreeResponse(List<ProjectNode> projects, List<TaskNode> tasks) {

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
            LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
            List<Integer> reminders, String color
    ) {}
}
