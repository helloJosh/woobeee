package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Milestones;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MilestoneRepository extends JpaRepository<Milestones, Long> {

    @Query(value = "SELECT * FROM milestones WHERE project_id IN (:projectIds) ORDER BY sort_order, id",
            nativeQuery = true)
    List<Milestones> findAllForProjects(@Param("projectIds") List<Long> projectIds);

    @Query(value = "SELECT * FROM milestones WHERE project_id = :projectId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Milestones> findAllForProject(@Param("projectId") Long projectId);

    /** 자기 자신을 포함한 자손 전체의 id. 명시적 캐스케이드 삭제와 순환 검사가 쓴다. */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM milestones WHERE id = :milestoneId
                UNION
                SELECT m.id FROM milestones m JOIN descendants d ON m.parent_id = d.id
            )
            SELECT id FROM descendants
            """, nativeQuery = true)
    List<Long> findSelfAndDescendantIds(@Param("milestoneId") Long milestoneId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM milestones WHERE id IN (:ids)", nativeQuery = true)
    void deleteAllByIds(@Param("ids") List<Long> ids);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM milestones WHERE project_id = :projectId", nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    /** SCHEDULE-AC-21/22 — 종료일이 지난(어제 이전) 마일스톤을 완료로. 미정(NULL)·당일, 그리고 마감 후 수동 수정(updated_at > 종료일)은 제외. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE milestones SET status = 'DONE', updated_at = CURRENT_TIMESTAMP
            WHERE project_id IN (SELECT id FROM projects WHERE member_id = :memberId)
              AND end_date < CURRENT_DATE AND status <> 'DONE'
              AND (updated_at IS NULL OR updated_at < end_date + 1)
            """, nativeQuery = true)
    int completeOverdueForMember(@Param("memberId") Long memberId);
}
