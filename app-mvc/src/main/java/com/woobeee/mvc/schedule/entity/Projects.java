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
@Table(name = "projects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Projects {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

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
    private Projects(Long memberId, String name, ScheduleStatus status,
                     LocalDate startDate, LocalDate endDate, int sortOrder) {
        this.memberId = memberId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sortOrder = sortOrder;
    }

    public static Projects create(Long memberId, String name, ScheduleStatus status,
                                  LocalDate startDate, LocalDate endDate) {
        return Projects.builder()
                .memberId(memberId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .sortOrder(0)
                .build();
    }

    public void update(String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate) {
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
