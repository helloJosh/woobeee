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

@Getter
@Entity
@Table(name = "tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tasks {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 마일스톤 몇 단 아래에 있든 유저의 전체 할 일(달력)을 조인 한 번에 가져오기 위한 중복 보유. */
    @Column(nullable = false)
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
    private Tasks(Long projectId, Long milestoneId, String name, ScheduleStatus status,
                  LocalDate startDate, LocalDate endDate, String color, int sortOrder) {
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    public static Tasks create(Long projectId, Long milestoneId, String name,
                               ScheduleStatus status, LocalDate startDate, LocalDate endDate,
                               String color) {
        return Tasks.builder()
                .projectId(projectId)
                .milestoneId(milestoneId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .color(color)
                .sortOrder(0)
                .build();
    }

    public void update(Long milestoneId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate, String color) {
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.color = color;
    }
}
