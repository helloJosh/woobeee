package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 할 일 밑의 이슈사항 한 건 (SCHEDULE-AC-40). 달력에는 나오지 않고 할 일 행 아래에 접혀 있다.
 * 소유권은 부모 할 일의 member_id 로 판별한다 — 이 행은 소유자를 따로 들고 있지 않다.
 */
@Getter
@Entity
@Table(name = "task_issues")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskIssues {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private boolean resolved;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private TaskIssues(Long taskId, String content) {
        this.taskId = taskId;
        this.content = content;
        this.resolved = false;
    }

    /** 새 이슈는 항상 미해결로 시작한다. */
    public static TaskIssues create(Long taskId, String content) {
        return new TaskIssues(taskId, content);
    }

    public void update(String content, boolean resolved) {
        this.content = content;
        this.resolved = resolved;
    }
}
