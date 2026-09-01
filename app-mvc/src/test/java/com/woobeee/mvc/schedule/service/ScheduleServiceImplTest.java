package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.schedule.api.request.PostMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PostProjectRequest;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.api.request.PutMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PutTaskRequest;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.api.response.TaskResponse;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.exception.ScheduleException;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {

    private static final String LOGIN = "me@example.com";
    private static final long MEMBER_ID = 7L;

    @Mock ProjectRepository projectRepository;
    @Mock MilestoneRepository milestoneRepository;
    @Mock TaskRepository taskRepository;
    @Mock ScheduleMemberResolver memberResolver;

    @InjectMocks ScheduleServiceImpl service;

    private Projects ownedProject(long id) {
        Projects p = Projects.create(MEMBER_ID, "p", ScheduleStatus.NOT_STARTED, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Projects foreignProject(long id) {
        Projects p = Projects.create(999L, "p", ScheduleStatus.NOT_STARTED, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Milestones milestone(long id, long projectId, Long parentId) {
        Milestones m = Milestones.create(projectId, parentId, "m", null, null, null);
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private void loggedIn() {
        when(memberResolver.requireMemberId(LOGIN)).thenReturn(MEMBER_ID);
    }

    /** SCHEDULE-AC-03 — 남의 프로젝트는 없는 프로젝트와 같은 얼굴을 한다. */
    @Test
    void writingIntoAnotherMembersProjectLooksLikeNotFound() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(foreignProject(10L)));

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(10L, null, "t", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.PROJECT_NOT_FOUND));
    }

    /** SCHEDULE-AC-04 — status 를 생략하면 NOT_STARTED. */
    @Test
    void creatingAProjectWithoutStatusDefaultsToNotStarted() {
        loggedIn();
        when(projectRepository.save(any(Projects.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createProject(LOGIN,
                new PostProjectRequest("새 프로젝트", null, null, null));

        assertThat(response.status()).isEqualTo("NOT_STARTED");
    }

    /** SCHEDULE-AC-11 — 종료일이 시작일보다 빠르면 거부. */
    @Test
    void endDateBeforeStartDateIsRejected() {
        loggedIn();

        assertThatThrownBy(() -> service.createProject(LOGIN,
                new PostProjectRequest("p", null,
                        LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1))))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE));
    }

    /** SCHEDULE-AC-06 — 다른 프로젝트의 마일스톤을 부모로 지정할 수 없다. */
    @Test
    void aParentFromAnotherProjectIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(55L)).thenReturn(Optional.of(milestone(55L, 20L, null)));

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, 55L, "m", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CROSS_PROJECT));
    }

    /** SCHEDULE-AC-07 — 깊이 5 를 넘는 생성은 거부된다. (1~5 체인 밑에 6번째) */
    @Test
    void creatingASixthLevelMilestoneIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        Milestones level5 = milestone(5L, 10L, 4L);
        when(milestoneRepository.findById(5L)).thenReturn(Optional.of(level5));
        when(milestoneRepository.findAllForProject(10L)).thenReturn(List.of(
                milestone(1L, 10L, null), milestone(2L, 10L, 1L), milestone(3L, 10L, 2L),
                milestone(4L, 10L, 3L), level5));

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, 5L, "level6", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.DEPTH_EXCEEDED));
    }

    /** SCHEDULE-AC-08 — 마일스톤을 자기 자손 아래로 옮길 수 없다. */
    @Test
    void movingAMilestoneUnderItsOwnDescendantIsRejected() {
        loggedIn();
        Milestones root = milestone(1L, 10L, null);
        Milestones child = milestone(2L, 10L, 1L);
        when(milestoneRepository.findById(1L)).thenReturn(Optional.of(root));
        when(milestoneRepository.findById(2L)).thenReturn(Optional.of(child));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findAllForProject(10L)).thenReturn(List.of(root, child));

        assertThatThrownBy(() -> service.updateMilestone(LOGIN, 1L,
                new PutMilestoneRequest(2L, "root", ScheduleStatus.NOT_STARTED, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CYCLE));
    }

    /** SCHEDULE-AC-09 — 생성된 할 일의 색은 팔레트에서 나온다. */
    @Test
    void aNewTaskGetsAColorFromThePalette() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(10L, null, "t", null, null, null));

        assertThat(ScheduleColors.PALETTE).contains(response.color());
    }

    /** SCHEDULE-AC-10 — #RRGGBB 가 아닌 색은 거부된다. */
    @Test
    void anInvalidHexColorIsRejected() {
        loggedIn();
        Tasks task = Tasks.create(10L, null, "t", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(task, "id", 3L);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));

        assertThatThrownBy(() -> service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "t", ScheduleStatus.DONE, null, null, "red")))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_COLOR));
    }

    /** SCHEDULE-AC-12 — 프로젝트 삭제는 할 일 → 마일스톤 → 프로젝트 순서로 전부 지운다. */
    @Test
    void deletingAProjectCascadesExplicitly() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(p));

        service.deleteProject(LOGIN, 10L);

        InOrder order = inOrder(taskRepository, milestoneRepository, projectRepository);
        order.verify(taskRepository).deleteAllForProject(10L);
        order.verify(milestoneRepository).deleteAllForProject(10L);
        order.verify(projectRepository).delete(p);
    }

    /** SCHEDULE-AC-13 — 마일스톤 삭제는 자손 id 를 모아 그 밑 할 일부터 지운다. */
    @Test
    void deletingAMilestoneRemovesItsSubtree() {
        loggedIn();
        Milestones root = milestone(1L, 10L, null);
        when(milestoneRepository.findById(1L)).thenReturn(Optional.of(root));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findSelfAndDescendantIds(1L)).thenReturn(List.of(1L, 2L, 3L));

        service.deleteMilestone(LOGIN, 1L);

        InOrder order = inOrder(taskRepository, milestoneRepository);
        order.verify(taskRepository).deleteAllForMilestones(List.of(1L, 2L, 3L));
        order.verify(milestoneRepository).deleteAllByIds(List.of(1L, 2L, 3L));
    }

    /** SCHEDULE-AC-02 + SCHEDULE-AC-14 — 트리는 3회 조회로 조립되고 중첩이 맞다. */
    @Test
    void treeIsAssembledFromThreeBatchQueries() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(p));
        Milestones root = milestone(1L, 10L, null);
        Milestones child = milestone(2L, 10L, 1L);
        when(milestoneRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(root, child));
        Tasks direct = Tasks.create(10L, null, "direct", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(direct, "id", 100L);
        Tasks nested = Tasks.create(10L, 2L, "nested", null, null, null, "#3b82f6");
        ReflectionTestUtils.setField(nested, "id", 101L);
        when(taskRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(direct, nested));

        GetScheduleTreeResponse tree = service.getTree(LOGIN);

        assertThat(tree.projects()).hasSize(1);
        var projectNode = tree.projects().get(0);
        assertThat(projectNode.tasks()).extracting(GetScheduleTreeResponse.TaskNode::name)
                .containsExactly("direct");
        assertThat(projectNode.milestones()).hasSize(1);
        var rootNode = projectNode.milestones().get(0);
        assertThat(rootNode.milestones()).hasSize(1);
        assertThat(rootNode.milestones().get(0).tasks())
                .extracting(GetScheduleTreeResponse.TaskNode::name).containsExactly("nested");
        // 루프 내 단건 조회 금지 — findById 는 트리 조립에 쓰이지 않는다
        verify(milestoneRepository, never()).findById(any());
        verify(taskRepository, never()).findById(any());
    }

    /** SCHEDULE-AC-05 — 없는 프로젝트 참조. */
    @Test
    void referencingAMissingProjectIsNotFound() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, null, "m", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.PROJECT_NOT_FOUND));
    }

    /** SCHEDULE-AC-06 — 할 일의 milestoneId 가 다른 프로젝트 소속이면 거부. */
    @Test
    void aTaskPointingAtAnotherProjectsMilestoneIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(55L)).thenReturn(Optional.of(milestone(55L, 20L, null)));

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(10L, 55L, "t", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CROSS_PROJECT));
    }

    /**
     * Fix 1 회귀 — 동시 PUT 이 이미 부모 순환(A.parent=B, B.parent=A)을 만든 뒤라도,
     * 그 체인을 타는 이동 검증은 무한 루프에 빠지지 않고 CYCLE 로 즉시 실패해야 한다.
     */
    @Test
    void movingATargetUnderAnAlreadyCyclicPairFailsPromptlyInsteadOfHanging() {
        loggedIn();
        Milestones a = milestone(1L, 10L, 2L); // A.parent = B
        Milestones b = milestone(2L, 10L, 1L); // B.parent = A
        Milestones c = milestone(3L, 10L, null);
        when(milestoneRepository.findById(3L)).thenReturn(Optional.of(c));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(1L)).thenReturn(Optional.of(a));
        when(milestoneRepository.findAllForProject(10L)).thenReturn(List.of(a, b, c));

        assertThatThrownBy(() -> service.updateMilestone(LOGIN, 3L,
                new PutMilestoneRequest(1L, "c", ScheduleStatus.NOT_STARTED, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CYCLE));
    }

    /** Fix 2 — 부모/마일스톤이 지워져 고아가 된 노드는 조용히 사라지지 않고 프로젝트 루트로 재부착된다. */
    @Test
    void orphanedMilestonesAndTasksReattachAtTheProjectRoot() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(p));
        Milestones orphanMilestone = milestone(5L, 10L, 77L); // parentId=77 — fetch 결과에 없다
        when(milestoneRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(orphanMilestone));
        Tasks orphanTask = Tasks.create(10L, 99L, "orphan-task", null, null, null, "#ef4444"); // milestoneId=99 도 없다
        ReflectionTestUtils.setField(orphanTask, "id", 200L);
        when(taskRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(orphanTask));

        GetScheduleTreeResponse tree = service.getTree(LOGIN);

        var projectNode = tree.projects().get(0);
        assertThat(projectNode.milestones()).extracting(GetScheduleTreeResponse.MilestoneNode::id)
                .containsExactly(5L);
        assertThat(projectNode.tasks()).extracting(GetScheduleTreeResponse.TaskNode::name)
                .containsExactly("orphan-task");
    }

    /**
     * Fix 6 (SCHEDULE-AC-07 이동 케이스) — 깊이 1~4 체인(m1~m4) 밑에 S(자신 1단 + 자식 1단, 높이 2)를
     * 옮기면 4(부모 깊이) + 2(옮기는 subtree 높이) = 6 > 5 로 거부돼야 한다.
     */
    @Test
    void movingASubtreeThatWouldPushTheDeepestDescendantPastFiveIsRejected() {
        loggedIn();
        Milestones m1 = milestone(1L, 10L, null);
        Milestones m2 = milestone(2L, 10L, 1L);
        Milestones m3 = milestone(3L, 10L, 2L);
        Milestones m4 = milestone(4L, 10L, 3L);
        Milestones s = milestone(5L, 10L, null); // 현재는 루트
        Milestones sChild = milestone(6L, 10L, 5L); // S 의 자식 — S 의 높이를 2로 만든다
        when(milestoneRepository.findById(5L)).thenReturn(Optional.of(s));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(4L)).thenReturn(Optional.of(m4));
        when(milestoneRepository.findAllForProject(10L))
                .thenReturn(List.of(m1, m2, m3, m4, s, sChild));

        assertThatThrownBy(() -> service.updateMilestone(LOGIN, 5L,
                new PutMilestoneRequest(4L, "s", ScheduleStatus.NOT_STARTED, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.DEPTH_EXCEEDED));
    }

    /** SCHEDULE-AC-21 — 트리 조회는 읽기 전에 세 층의 기한 경과 항목을 완료로 갱신한다. */
    @Test
    void getTreeCompletesOverdueItemsBeforeReading() {
        loggedIn();
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of());

        service.getTree(LOGIN);

        InOrder order = inOrder(projectRepository, milestoneRepository, taskRepository);
        order.verify(projectRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(milestoneRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(taskRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(projectRepository).findAllForMember(MEMBER_ID);
    }
}
