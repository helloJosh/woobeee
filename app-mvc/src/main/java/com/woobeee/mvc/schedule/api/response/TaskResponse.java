package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Tasks;

import java.time.LocalDate;

public record TaskResponse(
        Long id, Long projectId, Long milestoneId, String name, String status,
        LocalDate startDate, LocalDate endDate, String color
) {
    public static TaskResponse from(Tasks t) {
        return new TaskResponse(t.getId(), t.getProjectId(), t.getMilestoneId(), t.getName(),
                t.getStatus().name(), t.getStartDate(), t.getEndDate(), t.getColor());
    }
}
