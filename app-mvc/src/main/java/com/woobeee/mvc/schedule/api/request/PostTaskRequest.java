package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record PostTaskRequest(
        /** null 이면 어느 프로젝트에도 속하지 않은 무소속 할 일 (SCHEDULE-AC-31). */
        Long projectId,
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate,
        /** "HH:mm" — 선택 (SCHEDULE-AC-34). */
        LocalTime startTime,
        LocalTime endTime,
        /** 시작 전 알림(분). null 은 빈 목록 (SCHEDULE-AC-35). */
        List<Integer> reminders
) {}
