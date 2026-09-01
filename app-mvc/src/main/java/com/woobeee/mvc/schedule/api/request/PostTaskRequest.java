package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PostTaskRequest(
        /** null 이면 어느 프로젝트에도 속하지 않은 무소속 할 일 (SCHEDULE-AC-31). */
        Long projectId,
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
