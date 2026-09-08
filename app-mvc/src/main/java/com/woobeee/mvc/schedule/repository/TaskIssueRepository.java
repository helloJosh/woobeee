package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.TaskIssues;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskIssueRepository extends JpaRepository<TaskIssues, Long> {

    /** 트리 조립용 배치 조회 — 다섯 번째 배치, 루프 안 단건 조회 대신 (SCHEDULE-AC-14/41). */
    @Query(value = "SELECT * FROM task_issues WHERE task_id IN (:taskIds) ORDER BY id", nativeQuery = true)
    List<TaskIssues> findAllForTasks(@Param("taskIds") List<Long> taskIds);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_issues WHERE task_id = :taskId", nativeQuery = true)
    void deleteAllForTask(@Param("taskId") Long taskId);

    /** 프로젝트 캐스케이드 — tasks 를 지우기 전에 불러야 한다 (서브쿼리가 tasks 를 본다). */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_issues WHERE task_id IN (SELECT id FROM tasks WHERE project_id = :projectId)",
            nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    /** 마일스톤 캐스케이드 — 마찬가지로 tasks 삭제 전에. */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM task_issues WHERE task_id IN (SELECT id FROM tasks WHERE milestone_id IN (:milestoneIds))",
            nativeQuery = true)
    void deleteAllForMilestones(@Param("milestoneIds") List<Long> milestoneIds);
}
