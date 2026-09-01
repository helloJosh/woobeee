package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Tasks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Tasks, Long> {

    @Query(value = "SELECT * FROM tasks WHERE project_id IN (:projectIds) ORDER BY sort_order, id",
            nativeQuery = true)
    List<Tasks> findAllForProjects(@Param("projectIds") List<Long> projectIds);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM tasks WHERE project_id = :projectId", nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM tasks WHERE milestone_id IN (:milestoneIds)", nativeQuery = true)
    void deleteAllForMilestones(@Param("milestoneIds") List<Long> milestoneIds);

    /** SCHEDULE-AC-26 — 다이제스트: 오늘 마감인 할 일. */
    @Query(value = """
            SELECT t.* FROM tasks t
            JOIN projects p ON p.id = t.project_id
            WHERE p.member_id = :memberId AND t.end_date = CURRENT_DATE
            ORDER BY t.sort_order, t.id
            """, nativeQuery = true)
    List<Tasks> findDueTodayForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-26 — 다이제스트: 오늘 시작하는 할 일. */
    @Query(value = """
            SELECT t.* FROM tasks t
            JOIN projects p ON p.id = t.project_id
            WHERE p.member_id = :memberId AND t.start_date = CURRENT_DATE
            ORDER BY t.sort_order, t.id
            """, nativeQuery = true)
    List<Tasks> findStartingTodayForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-26/28 — 다이제스트: 기한이 지났는데 아직 완료가 아닌 할 일 (자동 완료 직전 수집용). */
    @Query(value = """
            SELECT t.* FROM tasks t
            JOIN projects p ON p.id = t.project_id
            WHERE p.member_id = :memberId AND t.end_date < CURRENT_DATE AND t.status <> 'DONE'
            ORDER BY t.sort_order, t.id
            """, nativeQuery = true)
    List<Tasks> findOverdueForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-21/22 — 종료일이 지난(어제 이전) 할 일을 완료로. 미정(NULL)·당일은 제외. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE tasks SET status = 'DONE', updated_at = CURRENT_TIMESTAMP
            WHERE project_id IN (SELECT id FROM projects WHERE member_id = :memberId)
              AND end_date < CURRENT_DATE AND status <> 'DONE'
            """, nativeQuery = true)
    int completeOverdueForMember(@Param("memberId") Long memberId);
}
