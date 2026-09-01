// 이동(parentId 변경)을 포함한다. 프로젝트 간 이동은 없다.
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PutMilestoneRequest(
        Long parentId,
        @NotBlank @Size(max = 200) String name,
        @NotNull ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
