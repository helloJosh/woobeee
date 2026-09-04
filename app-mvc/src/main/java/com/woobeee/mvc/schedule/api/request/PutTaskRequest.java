// color 는 null 이면 기존 값 유지.
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record PutTaskRequest(
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        @NotNull ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate,
        /** "HH:mm" — 선택 (SCHEDULE-AC-34). */
        LocalTime startTime,
        LocalTime endTime,
        /** 시작 전 알림(분) — 집합을 통째로 교체한다. null 은 빈 목록 (SCHEDULE-AC-35). */
        List<Integer> reminders,
        String color
) {}
