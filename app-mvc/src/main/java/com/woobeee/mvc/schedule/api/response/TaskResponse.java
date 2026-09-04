package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Tasks;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TaskResponse(
        Long id, Long projectId, Long milestoneId, String name, String status,
        LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
        List<Integer> reminders, String color
) {
    public static TaskResponse from(Tasks t, List<Integer> reminders) {
        return new TaskResponse(t.getId(), t.getProjectId(), t.getMilestoneId(), t.getName(),
                t.getStatus().name(), t.getStartDate(), t.getEndDate(), t.getStartTime(), t.getEndTime(),
                reminders, t.getColor());
    }
}
