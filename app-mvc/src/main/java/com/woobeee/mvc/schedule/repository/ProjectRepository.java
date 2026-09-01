package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Projects;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Projects, Long> {

    @Query(value = "SELECT * FROM projects WHERE member_id = :memberId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Projects> findAllForMember(@Param("memberId") Long memberId);
}
