package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Entity
@Table(name = "tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tasks {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 — 소유권은 프로젝트 경유가 아니라 이 컬럼으로 직접 판별한다 (무소속 할 일 지원). */
    @Column(nullable = false)
    private Long memberId;

    /** NULL = 어느 프로젝트에도 속하지 않은 무소속 할 일. */
    private Long projectId;

    /** NULL = 프로젝트 직속. */
    private Long milestoneId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 선택 입력 — 해당 날짜가 NULL 이면 시간도 NULL (SCHEDULE-AC-34). */
    private LocalTime startTime;

    private LocalTime endTime;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private Tasks(Long memberId, Long projectId, Long milestoneId, String name, ScheduleStatus status,
                  LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                  String color, int sortOrder) {
        this.memberId = memberId;
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    /** 시간 없는 하루 단위 할 일. */
    public static Tasks create(Long memberId, Long projectId, Long milestoneId, String name,
                               ScheduleStatus status, LocalDate startDate, LocalDate endDate,
                               String color) {
        return create(memberId, projectId, milestoneId, name, status, startDate, endDate, null, null, color);
    }

    public static Tasks create(Long memberId, Long projectId, Long milestoneId, String name,
                               ScheduleStatus status, LocalDate startDate, LocalDate endDate,
                               LocalTime startTime, LocalTime endTime, String color) {
        return Tasks.builder()
                .memberId(memberId)
                .projectId(projectId)
                .milestoneId(milestoneId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .startTime(startDate == null ? null : startTime)
                .endTime(endDate == null ? null : endTime)
                .color(color)
                .sortOrder(0)
                .build();
    }

    public void update(Long milestoneId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                       String color) {
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startTime = startDate == null ? null : startTime;
        this.endTime = endDate == null ? null : endTime;
        this.color = color;
    }

    /** 알림 발송 시각의 기준. 날짜나 시간이 없으면 null — 알림을 붙일 수 없다 (SCHEDULE-AC-35). */
    public LocalDateTime startAt() {
        return startDate == null || startTime == null ? null : LocalDateTime.of(startDate, startTime);
    }
}
