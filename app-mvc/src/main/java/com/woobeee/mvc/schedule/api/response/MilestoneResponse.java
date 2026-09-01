package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Milestones;

import java.time.LocalDate;

public record MilestoneResponse(
        Long id, Long projectId, Long parentId, String name, String status,
        LocalDate startDate, LocalDate endDate
) {
    public static MilestoneResponse from(Milestones m) {
        return new MilestoneResponse(m.getId(), m.getProjectId(), m.getParentId(), m.getName(),
                m.getStatus().name(), m.getStartDate(), m.getEndDate());
    }
}
