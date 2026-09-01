package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Projects;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Projects, Long> {

    @Query(value = "SELECT * FROM projects WHERE member_id = :memberId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Projects> findAllForMember(@Param("memberId") Long memberId);

    /** SCHEDULE-AC-21/22 — 종료일이 지난(어제 이전) 프로젝트를 완료로. 미정(NULL)·당일, 그리고 마감 후 수동 수정(updated_at > 종료일)은 제외. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE projects SET status = 'DONE', updated_at = CURRENT_TIMESTAMP
            WHERE member_id = :memberId AND end_date < CURRENT_DATE AND status <> 'DONE'
              AND (updated_at IS NULL OR updated_at < end_date + 1)
            """, nativeQuery = true)
    int completeOverdueForMember(@Param("memberId") Long memberId);
}
