package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PostTaskRequest(
        @NotNull Long projectId,
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
