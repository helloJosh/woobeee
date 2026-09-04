package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 할 일 시작 전 알림 한 건 (SCHEDULE-AC-35). {@code sentAt} 이 채워지면 보낸 것이다 —
 * 재시작 뒤 중복 발송을 막는 유일한 기록이므로 발송 성공 뒤에만 찍는다 (SCHEDULE-AC-36).
 */
@Getter
@Entity
@Table(name = "task_reminders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskReminders {

    /** 허용되는 값. 스키마의 CHECK 제약과 프론트 REMINDER_OPTIONS 와 같아야 한다. */
    public static final Set<Integer> ALLOWED_MINUTES = Set.of(10, 30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private int minutesBefore;

    private LocalDateTime sentAt;

    private TaskReminders(Long taskId, int minutesBefore) {
        this.taskId = taskId;
        this.minutesBefore = minutesBefore;
    }

    public static TaskReminders create(Long taskId, int minutesBefore) {
        return new TaskReminders(taskId, minutesBefore);
    }
}
