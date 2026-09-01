package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.Tasks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.schedule.repository")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ScheduleRepositoryTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired MilestoneRepository milestoneRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 마감 전 마지막 수정처럼 보이게 updated_at 을 종료일 하루 전으로 되돌린다. */
    private void ageToBeforeDeadline(String table, Long id) {
        jdbcTemplate.update(
                "UPDATE " + table + " SET updated_at = (end_date - INTERVAL '1 day') WHERE id = ?", id);
    }

    private Projects project(long memberId) {
        return projectRepository.save(
                Projects.create(memberId, "p", ScheduleStatus.NOT_STARTED, null, null));
    }

    /** SCHEDULE-AC-02 — 목록 조회는 소유자 것만 가져온다. */
    @Test
    void findAllForMemberReturnsOnlyThatMembersProjects() {
        Projects mine = project(101L);
        project(202L);

        List<Projects> found = projectRepository.findAllForMember(101L);

        assertThat(found).extracting(Projects::getId).containsExactly(mine.getId());
    }

    /** SCHEDULE-AC-13 — 재귀 CTE 가 자기+자손 전부를 모은다. */
    @Test
    void findSelfAndDescendantIdsWalksTheWholeSubtree() {
        Projects p = project(1L);
        Milestones root = milestoneRepository.save(
                Milestones.create(p.getId(), null, "root", null, null, null));
        Milestones child = milestoneRepository.save(
                Milestones.create(p.getId(), root.getId(), "child", null, null, null));
        Milestones grandChild = milestoneRepository.save(
                Milestones.create(p.getId(), child.getId(), "grandchild", null, null, null));
        // 다른 가지 — 딸려 오면 안 된다
        milestoneRepository.save(Milestones.create(p.getId(), null, "other", null, null, null));

        List<Long> ids = milestoneRepository.findSelfAndDescendantIds(root.getId());

        assertThat(ids).containsExactlyInAnyOrder(root.getId(), child.getId(), grandChild.getId());
    }

    /** SCHEDULE-AC-13 — 마일스톤 일괄 삭제와 그 밑 할 일 삭제. */
    @Test
    void deleteAllByIdsAndDeleteAllForMilestonesRemoveTheSubtree() {
        Projects p = project(1L);
        Milestones root = milestoneRepository.save(
                Milestones.create(p.getId(), null, "root", null, null, null));
        Milestones child = milestoneRepository.save(
                Milestones.create(p.getId(), root.getId(), "child", null, null, null));
        Tasks task = taskRepository.save(
                Tasks.create(p.getId(), child.getId(), "t", null, null, null, "#ef4444"));

        List<Long> ids = milestoneRepository.findSelfAndDescendantIds(root.getId());
        taskRepository.deleteAllForMilestones(ids);
        milestoneRepository.deleteAllByIds(ids);

        assertThat(taskRepository.findById(task.getId())).isEmpty();
        assertThat(milestoneRepository.findAllForProject(p.getId())).isEmpty();
    }

    /** SCHEDULE-AC-12 — 프로젝트 단위 일괄 삭제. */
    @Test
    void projectScopedDeletesRemoveEverythingUnderTheProject() {
        Projects p = project(1L);
        Milestones m = milestoneRepository.save(
                Milestones.create(p.getId(), null, "m", null, null, null));
        taskRepository.save(Tasks.create(p.getId(), m.getId(), "in-milestone", null, null, null, "#ef4444"));
        taskRepository.save(Tasks.create(p.getId(), null, "direct", null, null, null, "#ef4444"));

        taskRepository.deleteAllForProject(p.getId());
        milestoneRepository.deleteAllForProject(p.getId());

        assertThat(taskRepository.findAllForProjects(List.of(p.getId()))).isEmpty();
        assertThat(milestoneRepository.findAllForProject(p.getId())).isEmpty();
    }

    /** SCHEDULE-AC-26 — 다이제스트 조회는 오늘 마감/오늘 시작/기한 경과(미완료)를 소유자 기준으로 가른다. */
    @Test
    void digestQueriesPickTheRightTasksForTheRightMember() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();

        Projects mine = project(401L);
        Tasks dueToday = taskRepository.save(Tasks.create(
                mine.getId(), null, "due-today", ScheduleStatus.IN_PROGRESS, null, today, "#ef4444"));
        Tasks startingToday = taskRepository.save(Tasks.create(
                mine.getId(), null, "starting-today", ScheduleStatus.NOT_STARTED, today, null, "#ef4444"));
        Tasks overdue = taskRepository.saveAndFlush(Tasks.create(
                mine.getId(), null, "overdue", ScheduleStatus.IN_PROGRESS, null, yesterday, "#ef4444"));
        ageToBeforeDeadline("tasks", overdue.getId());
        taskRepository.save(Tasks.create(
                mine.getId(), null, "overdue-done", ScheduleStatus.DONE, null, yesterday, "#ef4444"));
        // 마감 후 손댄(updated_at = 지금) 항목은 자동 완료 대상이 아니므로 다이제스트에도 안 담는다
        taskRepository.save(Tasks.create(
                mine.getId(), null, "overdue-reopened", ScheduleStatus.IN_PROGRESS, null, yesterday, "#ef4444"));
        Projects others = project(402L);
        taskRepository.save(Tasks.create(
                others.getId(), null, "not-mine", ScheduleStatus.IN_PROGRESS, null, today, "#ef4444"));

        assertThat(taskRepository.findDueTodayForMember(401L))
                .extracting(Tasks::getId).containsExactly(dueToday.getId());
        assertThat(taskRepository.findStartingTodayForMember(401L))
                .extracting(Tasks::getId).containsExactly(startingToday.getId());
        assertThat(taskRepository.findOverdueForMember(401L))
                .extracting(Tasks::getId).containsExactly(overdue.getId());
    }

    /**
     * SCHEDULE-AC-21/22 — 마감 전 마지막으로 수정된 기한 경과 항목은 세 층 모두 완료로.
     * 미정·당일·남의 것, 그리고 **마감이 지난 뒤 사용자가 직접 수정한 항목**(updated_at > 종료일,
     * 배지 클릭 등)은 유지된다 — 수동 변경이 자동 완료를 이긴다.
     */
    @Test
    void overdueItemsFlipToDoneWhileOpenEndedDueTodayOthersAndManualOverridesSurvive() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();

        Projects overdueProject = projectRepository.saveAndFlush(Projects.create(
                301L, "overdue", ScheduleStatus.IN_PROGRESS, null, yesterday));
        Milestones overdueMilestone = milestoneRepository.saveAndFlush(Milestones.create(
                overdueProject.getId(), null, "m", ScheduleStatus.NOT_STARTED, null, yesterday));
        Tasks overdueTask = taskRepository.saveAndFlush(Tasks.create(
                overdueProject.getId(), null, "t", ScheduleStatus.IN_PROGRESS, null, yesterday, "#ef4444"));
        ageToBeforeDeadline("projects", overdueProject.getId());
        ageToBeforeDeadline("milestones", overdueMilestone.getId());
        ageToBeforeDeadline("tasks", overdueTask.getId());

        // 마감이 지난 뒤 손댄 항목 — 방금 저장했으므로 updated_at = 지금(> 종료일)
        Tasks manuallyReopened = taskRepository.saveAndFlush(Tasks.create(
                overdueProject.getId(), null, "reopened", ScheduleStatus.NOT_STARTED, null, yesterday, "#ef4444"));
        Tasks openEnded = taskRepository.saveAndFlush(Tasks.create(
                overdueProject.getId(), null, "open", ScheduleStatus.IN_PROGRESS, null, null, "#ef4444"));
        Tasks dueToday = taskRepository.saveAndFlush(Tasks.create(
                overdueProject.getId(), null, "today", ScheduleStatus.IN_PROGRESS, null, today, "#ef4444"));
        Projects othersProject = projectRepository.saveAndFlush(Projects.create(
                999L, "other", ScheduleStatus.IN_PROGRESS, null, yesterday));
        ageToBeforeDeadline("projects", othersProject.getId());

        projectRepository.completeOverdueForMember(301L);
        milestoneRepository.completeOverdueForMember(301L);
        taskRepository.completeOverdueForMember(301L);

        assertThat(projectRepository.findById(overdueProject.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.DONE);
        assertThat(milestoneRepository.findById(overdueMilestone.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.DONE);
        assertThat(taskRepository.findById(overdueTask.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.DONE);
        assertThat(taskRepository.findById(manuallyReopened.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.NOT_STARTED);
        assertThat(taskRepository.findById(openEnded.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.IN_PROGRESS);
        assertThat(taskRepository.findById(dueToday.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.IN_PROGRESS);
        assertThat(projectRepository.findById(othersProject.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.IN_PROGRESS);
    }
}
