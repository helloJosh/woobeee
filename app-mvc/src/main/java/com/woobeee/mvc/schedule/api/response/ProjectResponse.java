package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Projects;

import java.time.LocalDate;

public record ProjectResponse(
        Long id, String name, String status, LocalDate startDate, LocalDate endDate
) {
    public static ProjectResponse from(Projects p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getStatus().name(),
                p.getStartDate(), p.getEndDate());
    }
}
