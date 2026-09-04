package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.TaskReminders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskReminderRepository extends JpaRepository<TaskReminders, Long> {

    /** 트리 조립용 배치 조회 — 루프 안 단건 조회 대신 (SCHEDULE-AC-14). */
    @Query(value = "SELECT * FROM task_reminders WHERE task_id IN (:taskIds) ORDER BY minutes_before",
            nativeQuery = true)
    List<TaskReminders> findAllForTasks(@Param("taskIds") List<Long> taskIds);

    @Query(value = "SELECT * FROM task_reminders WHERE task_id = :taskId ORDER BY minutes_before",
            nativeQuery = true)
    List<TaskReminders> findAllForTask(@Param("taskId") Long taskId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_reminders WHERE task_id = :taskId", nativeQuery = true)
    void deleteAllForTask(@Param("taskId") Long taskId);

    /** 프로젝트 캐스케이드 — tasks 를 지우기 전에 불러야 한다 (서브쿼리가 tasks 를 본다). */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_reminders WHERE task_id IN (SELECT id FROM tasks WHERE project_id = :projectId)",
            nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    /** 마일스톤 캐스케이드 — 마찬가지로 tasks 삭제 전에. */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_reminders WHERE task_id IN (SELECT id FROM tasks WHERE milestone_id IN (:milestoneIds))",
            nativeQuery = true)
    void deleteAllForMilestones(@Param("milestoneIds") List<Long> milestoneIds);

    /**
     * SCHEDULE-AC-36 — 지금 보내야 할 알림. 미발송이고, 발송 시각(시작 - N분)이 왔고, 아직 시작 전인 것만.
     * 시작 시각을 넘긴 알림은 영영 빠진다 — 서버가 죽어 있던 사이의 알림을 뒤늦게 보내지 않는다.
     * webhook 미등록 멤버의 알림은 조건에서 걸러져 그대로 남는다.
     */
    @Query(value = """
            SELECT r.id AS "reminderId", r.minutes_before AS "minutesBefore",
                   t.name AS "taskName", p.name AS "projectName",
                   t.start_time AS "startTime", m.slack_webhook_url AS "webhookUrl"
            FROM task_reminders r
            JOIN tasks t ON t.id = r.task_id
            JOIN members m ON m.id = t.member_id
            LEFT JOIN projects p ON p.id = t.project_id
            WHERE r.sent_at IS NULL
              AND t.start_date IS NOT NULL AND t.start_time IS NOT NULL
              AND m.slack_webhook_url IS NOT NULL
              AND (t.start_date + t.start_time) - make_interval(mins => r.minutes_before) <= :now
              AND (t.start_date + t.start_time) > :now
            ORDER BY r.id
            """, nativeQuery = true)
    List<DueReminder> findDue(@Param("now") LocalDateTime now);

    /** 발송 성공 직후 한 건씩 — 실패한 건은 남겨 다음 분에 재시도된다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE task_reminders SET sent_at = :now WHERE id = :id", nativeQuery = true)
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);
}
