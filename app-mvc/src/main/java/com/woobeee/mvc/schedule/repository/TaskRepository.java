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
}
