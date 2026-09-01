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
@Table(name = "milestones")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Milestones {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** NULL = 프로젝트 직속. 셀프 참조(재귀 트리). */
    private Long parentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private Milestones(Long projectId, Long parentId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate, int sortOrder) {
        this.projectId = projectId;
        this.parentId = parentId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sortOrder = sortOrder;
    }

    public static Milestones create(Long projectId, Long parentId, String name,
                                    ScheduleStatus status, LocalDate startDate, LocalDate endDate) {
        return Milestones.builder()
                .projectId(projectId)
                .parentId(parentId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .sortOrder(0)
                .build();
    }

    public void update(Long parentId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate) {
        this.parentId = parentId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
