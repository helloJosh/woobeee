package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.schedule.api.request.PostMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PostProjectRequest;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.api.request.PutMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PutNotificationRequest;
import com.woobeee.mvc.schedule.api.request.PutTaskRequest;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.api.response.TaskResponse;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.TaskReminders;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.exception.ScheduleException;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskReminderRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock TaskReminderRepository reminderRepository;
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
                new PostTaskRequest(10L, null, "t", null, null, null, null, null, null)))
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
                new PostTaskRequest(10L, null, "t", null, null, null, null, null, null));

        assertThat(ScheduleColors.PALETTE).contains(response.color());
    }

    /** SCHEDULE-AC-10 — #RRGGBB 가 아닌 색은 거부된다. */
    @Test
    void anInvalidHexColorIsRejected() {
        loggedIn();
        Tasks task = Tasks.create(MEMBER_ID, 10L, null, "t", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(task, "id", 3L);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "t", ScheduleStatus.DONE, null, null, null, null, null, "red")))
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

        InOrder order = inOrder(reminderRepository, taskRepository, milestoneRepository, projectRepository);
        order.verify(reminderRepository).deleteAllForProject(10L); // tasks 를 지우기 전에 — 서브쿼리가 tasks 를 본다
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

        InOrder order = inOrder(reminderRepository, taskRepository, milestoneRepository);
        order.verify(reminderRepository).deleteAllForMilestones(List.of(1L, 2L, 3L));
        order.verify(taskRepository).deleteAllForMilestones(List.of(1L, 2L, 3L));
        order.verify(milestoneRepository).deleteAllByIds(List.of(1L, 2L, 3L));
    }

    /** SCHEDULE-AC-02 + SCHEDULE-AC-14 — 트리는 4회 배치 조회(프로젝트/마일스톤/할 일/알림)로 조립되고 중첩이 맞다. */
    @Test
    void treeIsAssembledFromFourBatchQueries() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(p));
        Milestones root = milestone(1L, 10L, null);
        Milestones child = milestone(2L, 10L, 1L);
        when(milestoneRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(root, child));
        Tasks direct = Tasks.create(MEMBER_ID, 10L, null, "direct", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(direct, "id", 100L);
        Tasks nested = Tasks.create(MEMBER_ID, 10L, 2L, "nested", null, null, null, "#3b82f6");
        ReflectionTestUtils.setField(nested, "id", 101L);
        Tasks standalone = Tasks.create(MEMBER_ID, null, null, "standalone", null, null, null, "#f97316");
        ReflectionTestUtils.setField(standalone, "id", 102L);
        when(taskRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(direct, nested, standalone));
        // 알림은 할 일 id 를 모아 한 번에 (SCHEDULE-AC-14)
        when(reminderRepository.findAllForTasks(List.of(100L, 101L, 102L)))
                .thenReturn(List.of(TaskReminders.create(101L, 10), TaskReminders.create(101L, 30)));

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
        assertThat(rootNode.milestones().get(0).tasks().get(0).reminders()).containsExactly(10, 30);
        assertThat(projectNode.tasks().get(0).reminders()).isEmpty();
        // 무소속 할 일은 최상위 tasks 로 (SCHEDULE-AC-31)
        assertThat(tree.tasks()).extracting(GetScheduleTreeResponse.TaskNode::name)
                .containsExactly("standalone");
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
                new PostTaskRequest(10L, 55L, "t", null, null, null, null, null, null)))
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
        Tasks orphanTask = Tasks.create(MEMBER_ID, 10L, 99L, "orphan-task", null, null, null, "#ef4444"); // milestoneId=99 도 없다
        ReflectionTestUtils.setField(orphanTask, "id", 200L);
        when(taskRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(orphanTask));

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

    /** SCHEDULE-AC-24 — hooks.slack.com 으로 시작하지 않는 URL 은 거부된다. */
    @Test
    void aNonSlackWebhookUrlIsRejected() {
        Member member = Member.create("sub", LOGIN, "me", true, true);
        when(memberResolver.requireMember(LOGIN)).thenReturn(member);

        assertThatThrownBy(() -> service.updateNotification(LOGIN,
                new PutNotificationRequest("https://example.com/hook")))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_WEBHOOK_URL));
        assertThat(member.getSlackWebhookUrl()).isNull();
    }

    /** SCHEDULE-AC-25 — 웹훅 등록·조회·해제는 본인 멤버 행에만 닿는다. */
    @Test
    void webhookUrlIsStoredAndCleared() {
        Member member = Member.create("sub", LOGIN, "me", true, true);
        when(memberResolver.requireMember(LOGIN)).thenReturn(member);

        var saved = service.updateNotification(LOGIN,
                new PutNotificationRequest("https://hooks.slack.com/services/T000/B000/xxx"));

        assertThat(saved.webhookUrl()).isEqualTo("https://hooks.slack.com/services/T000/B000/xxx");
        assertThat(service.getNotification(LOGIN).webhookUrl())
                .isEqualTo("https://hooks.slack.com/services/T000/B000/xxx");

        service.deleteNotification(LOGIN);

        assertThat(service.getNotification(LOGIN).webhookUrl()).isNull();
    }

    /** SCHEDULE-AC-21 — 트리 조회는 읽기 전에 세 층의 기한 경과 항목을 완료로 갱신한다. */
    @Test
    void getTreeCompletesOverdueItemsBeforeReading() {
        loggedIn();
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of());
        when(taskRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of());

        service.getTree(LOGIN);

        InOrder order = inOrder(projectRepository, milestoneRepository, taskRepository);
        order.verify(projectRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(milestoneRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(taskRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(projectRepository).findAllForMember(MEMBER_ID);
    }

    /** SCHEDULE-AC-31 — projectId 없이 만들면 무소속 할 일로 저장된다. */
    @Test
    void aTaskWithoutAProjectIsCreatedStandalone() {
        loggedIn();
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(null, null, "장보기", null, null, null, null, null, null));

        assertThat(response.projectId()).isNull();
        assertThat(response.milestoneId()).isNull();
        assertThat(ScheduleColors.PALETTE).contains(response.color());
    }

    /** SCHEDULE-AC-31 — 무소속 할 일은 마일스톤에 붙을 수 없다. */
    @Test
    void aStandaloneTaskCannotJoinAMilestone() {
        loggedIn();

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(null, 5L, "장보기", null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CROSS_PROJECT));
    }

    /** SCHEDULE-AC-31 — 남의 할 일은 없는 할 일과 같은 얼굴을 한다 (memberId 직접 판별). */
    @Test
    void anotherMembersTaskLooksLikeNotFound() {
        loggedIn();
        Tasks foreign = Tasks.create(999L, null, null, "남의 것", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(foreign, "id", 8L);
        when(taskRepository.findById(8L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deleteTask(LOGIN, 8L))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.TASK_NOT_FOUND));
    }

    /* ===== SCHEDULE-AC-34 ~ 35 — 시간과 시작 전 알림 ===== */

    private Tasks ownedTask(long id, LocalDate start, LocalTime startTime) {
        Tasks task = Tasks.create(MEMBER_ID, null, null, "t", null, start, start, startTime, null, "#ef4444");
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    /** SCHEDULE-AC-34 — 같은 날짜에서 종료 시간이 시작 시간보다 빠르면 날짜 범위 오류다. */
    @Test
    void anEndTimeBeforeTheStartTimeOnTheSameDayIsRejected() {
        loggedIn();
        LocalDate day = LocalDate.of(2026, 9, 4);

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, day, day,
                        LocalTime.of(15, 0), LocalTime.of(14, 0), null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE));
    }

    /** SCHEDULE-AC-34 — 날짜가 다르면 시간 순서는 보지 않고, 응답에 시간이 그대로 실린다. */
    @Test
    void timesAreStoredAndEchoedWhenDatesDiffer() {
        loggedIn();
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5),
                        LocalTime.of(15, 0), LocalTime.of(9, 0), null));

        assertThat(response.startTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.reminders()).isEmpty();
    }

    /** SCHEDULE-AC-34 — 날짜 없는 시간은 저장되지 않는다(정규화). */
    @Test
    void aTimeWithoutItsDateIsDropped() {
        loggedIn();
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, null, null, LocalTime.of(9, 0), LocalTime.of(10, 0), null));

        assertThat(response.startTime()).isNull();
        assertThat(response.endTime()).isNull();
    }

    /** SCHEDULE-AC-35 — 알림은 시작 날짜와 시간이 둘 다 있어야 붙는다. */
    @Test
    void aReminderWithoutAStartTimeIsRejected() {
        loggedIn();

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, LocalDate.of(2026, 9, 4), null, null, null, List.of(10))))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.REMINDER_NEEDS_START_TIME));
    }

    /** SCHEDULE-AC-35 — 10·30 이외의 값은 거부된다. */
    @Test
    void anUnknownReminderOffsetIsRejected() {
        loggedIn();

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, LocalDate.of(2026, 9, 4), null,
                        LocalTime.of(9, 0), null, List.of(15))))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_REMINDER));
    }

    /** SCHEDULE-AC-35 — 생성 시 알림 행이 저장되고(중복 제거·정렬) 응답에 실린다. */
    @Test
    void remindersAreSavedOnCreate() {
        loggedIn();
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> {
            Tasks t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 42L);
            return t;
        });

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(null, null, "t", null, LocalDate.of(2026, 9, 4), null,
                        LocalTime.of(9, 0), null, List.of(30, 10, 30)));

        assertThat(response.reminders()).containsExactly(10, 30);
        verify(reminderRepository).saveAll(argThat((Iterable<TaskReminders> rows) -> {
            List<Integer> minutes = new java.util.ArrayList<>();
            rows.forEach(r -> minutes.add(r.getMinutesBefore()));
            return minutes.equals(List.of(10, 30));
        }));
    }

    /** SCHEDULE-AC-35 — 시작 일시와 알림 집합이 그대로면 행을 건드리지 않는다 (이미 보낸 알림이 다시 나가지 않게). */
    @Test
    void unchangedRemindersAreLeftAloneOnUpdate() {
        loggedIn();
        LocalDate day = LocalDate.of(2026, 9, 4);
        Tasks task = ownedTask(3L, day, LocalTime.of(9, 0));
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(reminderRepository.findAllForTask(3L)).thenReturn(List.of(TaskReminders.create(3L, 10)));

        TaskResponse response = service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "renamed", ScheduleStatus.IN_PROGRESS, day, day,
                        LocalTime.of(9, 0), null, List.of(10), null));

        assertThat(response.reminders()).containsExactly(10);
        verify(reminderRepository, never()).deleteAllForTask(any());
        verify(reminderRepository, never()).saveAll(any());
    }

    /** SCHEDULE-AC-35 — 시작 시간이 바뀌면 같은 집합이라도 삭제 후 재생성(sent_at 리셋). */
    @Test
    void aChangedStartTimeRecreatesTheReminders() {
        loggedIn();
        LocalDate day = LocalDate.of(2026, 9, 4);
        Tasks task = ownedTask(3L, day, LocalTime.of(9, 0));
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(reminderRepository.findAllForTask(3L)).thenReturn(List.of(TaskReminders.create(3L, 10)));

        service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "t", ScheduleStatus.NOT_STARTED, day, day,
                        LocalTime.of(10, 0), null, List.of(10), null));

        InOrder order = inOrder(reminderRepository);
        order.verify(reminderRepository).deleteAllForTask(3L);
        order.verify(reminderRepository).saveAll(any());
    }

    /** SCHEDULE-AC-35 — 알림을 전부 빼면 행만 지운다. */
    @Test
    void clearingRemindersDeletesWithoutRecreating() {
        loggedIn();
        LocalDate day = LocalDate.of(2026, 9, 4);
        Tasks task = ownedTask(3L, day, LocalTime.of(9, 0));
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(reminderRepository.findAllForTask(3L)).thenReturn(List.of(TaskReminders.create(3L, 10)));

        TaskResponse response = service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "t", ScheduleStatus.NOT_STARTED, day, day,
                        LocalTime.of(9, 0), null, List.of(), null));

        assertThat(response.reminders()).isEmpty();
        verify(reminderRepository).deleteAllForTask(3L);
        verify(reminderRepository, never()).saveAll(any());
    }

    /** 할 일 삭제는 알림 행부터 지운다. */
    @Test
    void deletingATaskRemovesItsReminders() {
        loggedIn();
        Tasks task = ownedTask(3L, null, null);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));

        service.deleteTask(LOGIN, 3L);

        InOrder order = inOrder(reminderRepository, taskRepository);
        order.verify(reminderRepository).deleteAllForTask(3L);
        order.verify(taskRepository).delete(task);
    }
}
