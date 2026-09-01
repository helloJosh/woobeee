package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Tasks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Tasks, Long> {

    /** 무소속 포함 내 할 일 전부 — 트리·달력의 단일 조회 (SCHEDULE-AC-31). */
    @Query(value = "SELECT * FROM tasks WHERE member_id = :memberId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Tasks> findAllForMember(@Param("memberId") Long memberId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM tasks WHERE project_id = :projectId", nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM tasks WHERE milestone_id IN (:milestoneIds)", nativeQuery = true)
    void deleteAllForMilestones(@Param("milestoneIds") List<Long> milestoneIds);

    /** SCHEDULE-AC-26 — 다이제스트: 오늘 마감인 할 일 (무소속 포함). */
    @Query(value = """
            SELECT * FROM tasks
            WHERE member_id = :memberId AND end_date = CURRENT_DATE
            ORDER BY sort_order, id
            """, nativeQuery = true)
    List<Tasks> findDueTodayForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-26 — 다이제스트: 오늘 시작하는 할 일 (무소속 포함). */
    @Query(value = """
            SELECT * FROM tasks
            WHERE member_id = :memberId AND start_date = CURRENT_DATE
            ORDER BY sort_order, id
            """, nativeQuery = true)
    List<Tasks> findStartingTodayForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-26/28 — 다이제스트: 기한이 지났는데 아직 완료가 아닌 할 일 — 자동 완료와 같은 규칙(마감 후 수동 수정 제외). */
    @Query(value = """
            SELECT * FROM tasks
            WHERE member_id = :memberId AND end_date < CURRENT_DATE AND status <> 'DONE'
              AND (updated_at IS NULL OR updated_at < end_date + 1)
            ORDER BY sort_order, id
            """, nativeQuery = true)
    List<Tasks> findOverdueForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-21/22 — 종료일이 지난(어제 이전) 할 일을 완료로. 미정(NULL)·당일, 그리고 마감 후 수동 수정(updated_at > 종료일)은 제외. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE tasks SET status = 'DONE', updated_at = CURRENT_TIMESTAMP
            WHERE member_id = :memberId
              AND end_date < CURRENT_DATE AND status <> 'DONE'
              AND (updated_at IS NULL OR updated_at < end_date + 1)
            """, nativeQuery = true)
    int completeOverdueForMember(@Param("memberId") Long memberId);
}
