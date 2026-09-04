package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.schedule.api.request.*;
import com.woobeee.mvc.schedule.api.response.*;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.TaskReminders;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskReminderRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ScheduleServiceImpl implements ScheduleService {

    static final int MAX_MILESTONE_DEPTH = 5;

    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final TaskRepository taskRepository;
    private final TaskReminderRepository reminderRepository;
    private final ScheduleMemberResolver memberResolver;

    /* ===== 공통 검증 ===== */

    /** 소유하지 않은(또는 없는) 프로젝트는 같은 404 로 응답해 존재 여부를 흘리지 않는다. */
    private Projects ownedProject(Long memberId, Long projectId) {
        return projectRepository.findById(projectId)
                .filter(p -> p.getMemberId().equals(memberId))
                .orElseThrow(ScheduleErrorCode.PROJECT_NOT_FOUND::asException);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ScheduleErrorCode.INVALID_DATE_RANGE.asException();
        }
    }

    /** SCHEDULE-AC-34 — 같은 날짜에 둘 다 시간이 있으면 종료가 시작보다 빠를 수 없다. 날짜 없는 시간은 무시된다. */
    private static void validateTimes(LocalDate start, LocalDate end, LocalTime startTime, LocalTime endTime) {
        validateDates(start, end);
        if (start != null && end != null && start.equals(end)
                && startTime != null && endTime != null && endTime.isBefore(startTime)) {
            throw ScheduleErrorCode.INVALID_DATE_RANGE.asException();
        }
    }

    /**
     * SCHEDULE-AC-35 — 알림 값은 10·30 만, 그리고 시작 날짜와 시간이 둘 다 있어야 붙일 수 있다.
     * 정렬·중복 제거된 목록을 돌려준다.
     */
    private static List<Integer> validateReminders(List<Integer> reminders, LocalDate start, LocalTime startTime) {
        if (reminders == null || reminders.isEmpty()) {
            return List.of();
        }
        for (Integer m : reminders) {
            if (m == null || !TaskReminders.ALLOWED_MINUTES.contains(m)) {
                throw ScheduleErrorCode.INVALID_REMINDER.asException();
            }
        }
        if (start == null || startTime == null) {
            throw ScheduleErrorCode.REMINDER_NEEDS_START_TIME.asException();
        }
        return reminders.stream().distinct().sorted().toList();
    }

    private static List<Integer> minutesOf(List<TaskReminders> rows) {
        List<Integer> out = new ArrayList<>();
        for (TaskReminders r : rows) {
            out.add(r.getMinutesBefore());
        }
        return out;
    }

    private void saveReminders(Long taskId, List<Integer> minutes) {
        List<TaskReminders> rows = new ArrayList<>();
        for (Integer m : minutes) {
            rows.add(TaskReminders.create(taskId, m));
        }
        if (!rows.isEmpty()) {
            reminderRepository.saveAll(rows);
        }
    }

    /**
     * SCHEDULE-AC-35 — 시작 일시와 집합이 모두 같으면 행을 건드리지 않아 이미 보낸 알림이 다시 나가지 않는다.
     * 하나라도 다르면 삭제 후 재생성(sent_at 리셋).
     */
    private List<Integer> replaceRemindersIfChanged(Tasks task, LocalDateTime previousStartAt, List<Integer> wanted) {
        List<Integer> current = minutesOf(reminderRepository.findAllForTask(task.getId()));
        if (current.equals(wanted) && Objects.equals(previousStartAt, task.startAt())) {
            return current;
        }
        reminderRepository.deleteAllForTask(task.getId());
        saveReminders(task.getId(), wanted);
        return wanted;
    }

    /** parentId(또는 milestoneId)가 이 프로젝트의 마일스톤인지. null 이면 통과. */
    private Milestones requireMilestoneInProject(Long milestoneId, Long projectId) {
        Milestones milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(ScheduleErrorCode.MILESTONE_NOT_FOUND::asException);
        if (!milestone.getProjectId().equals(projectId)) {
            throw ScheduleErrorCode.CROSS_PROJECT.asException();
        }
        return milestone;
    }

    /** 프로젝트의 마일스톤 전체를 부모 체인 지도로. 깊이·순환 검사가 쓴다. */
    private Map<Long, Long> parentIndex(Long projectId) {
        Map<Long, Long> parents = new HashMap<>();
        for (Milestones m : milestoneRepository.findAllForProject(projectId)) {
            parents.put(m.getId(), m.getParentId());
        }
        return parents;
    }

    /** 루트 직속 = 1. */
    private static int depthOf(Long milestoneId, Map<Long, Long> parents) {
        int depth = 0;
        Long cursor = milestoneId;
        while (cursor != null) {
            depth++;
            cursor = parents.get(cursor);
            if (depth > parents.size() + 1) {
                // 데이터가 이미 망가져 순환이면 무한 루프 대신 실패시킨다
                throw ScheduleErrorCode.CYCLE.asException();
            }
        }
        return depth;
    }

    /** subtree 의 상대 높이(자기 자신 = 1). */
    private static int heightOf(Long milestoneId, Map<Long, Long> parents) {
        Map<Long, List<Long>> children = new HashMap<>();
        parents.forEach((id, parent) -> children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id));
        return heightWalk(milestoneId, children);
    }

    private static int heightWalk(Long id, Map<Long, List<Long>> children) {
        return heightWalk(id, children, new HashSet<>());
    }

    private static int heightWalk(Long id, Map<Long, List<Long>> children, Set<Long> visited) {
        if (!visited.add(id)) {
            // 데이터가 이미 망가져 순환이면 무한 재귀 대신 실패시킨다
            throw ScheduleErrorCode.CYCLE.asException();
        }
        int max = 0;
        for (Long child : children.getOrDefault(id, List.of())) {
            max = Math.max(max, heightWalk(child, children, visited));
        }
        return max + 1;
    }

    /* ===== 트리 조회 ===== */

    // readOnly 가 아닌 이유: 조회 직전 자동 완료 갱신(SCHEDULE-AC-21)을 같은 트랜잭션에서 실행한다.
    @Override
    public GetScheduleTreeResponse getTree(String loginId) {
        Long memberId = memberResolver.requireMemberId(loginId);

        // 종료일이 지난 항목을 세 층 모두 완료로 — 미정(NULL)·당일은 SQL 조건이 제외한다 (AC-22).
        projectRepository.completeOverdueForMember(memberId);
        milestoneRepository.completeOverdueForMember(memberId);
        taskRepository.completeOverdueForMember(memberId);

        List<Projects> projects = projectRepository.findAllForMember(memberId);
        List<Tasks> allTasks = taskRepository.findAllForMember(memberId);
        Map<Long, List<Integer>> remindersByTask = remindersByTask(allTasks);
        if (projects.isEmpty()) {
            return new GetScheduleTreeResponse(List.of(), toTaskNodes(allTasks, remindersByTask));
        }

        List<Long> projectIds = new ArrayList<>();
        for (Projects p : projects) {
            projectIds.add(p.getId());
        }
        Set<Long> projectIdSet = new HashSet<>(projectIds);
        List<Milestones> allMilestones = milestoneRepository.findAllForProjects(projectIds);

        // 동시 쓰기로 부모/마일스톤이 지워지면 고아가 생길 수 있다 — 조용히 누락시키지 않고
        // 프로젝트 루트로 재부착해 무결성 구멍을 눈에 보이게 한다.
        Set<Long> milestoneIds = new HashSet<>();
        for (Milestones m : allMilestones) {
            milestoneIds.add(m.getId());
        }

        Map<Long, List<Milestones>> rootMilestonesByProject = new HashMap<>();
        Map<Long, List<Milestones>> childMilestonesByParent = new HashMap<>();
        for (Milestones m : allMilestones) {
            if (m.getParentId() == null || !milestoneIds.contains(m.getParentId())) {
                rootMilestonesByProject.computeIfAbsent(m.getProjectId(), k -> new ArrayList<>()).add(m);
            } else {
                childMilestonesByParent.computeIfAbsent(m.getParentId(), k -> new ArrayList<>()).add(m);
            }
        }

        Map<Long, List<Tasks>> rootTasksByProject = new HashMap<>();
        Map<Long, List<Tasks>> tasksByMilestone = new HashMap<>();
        List<Tasks> standaloneTasks = new ArrayList<>();
        for (Tasks t : allTasks) {
            // 무소속(projectId NULL) — 사라진 프로젝트를 가리키는 고아도 숨기지 않고 여기로 (AC-31)
            if (t.getProjectId() == null || !projectIdSet.contains(t.getProjectId())) {
                standaloneTasks.add(t);
            } else if (t.getMilestoneId() == null || !milestoneIds.contains(t.getMilestoneId())) {
                rootTasksByProject.computeIfAbsent(t.getProjectId(), k -> new ArrayList<>()).add(t);
            } else {
                tasksByMilestone.computeIfAbsent(t.getMilestoneId(), k -> new ArrayList<>()).add(t);
            }
        }

        List<GetScheduleTreeResponse.ProjectNode> projectNodes = new ArrayList<>();
        for (Projects p : projects) {
            List<GetScheduleTreeResponse.MilestoneNode> milestoneNodes = buildMilestoneNodes(
                    rootMilestonesByProject.getOrDefault(p.getId(), List.of()),
                    childMilestonesByParent, tasksByMilestone, remindersByTask);
            List<GetScheduleTreeResponse.TaskNode> taskNodes =
                    toTaskNodes(rootTasksByProject.getOrDefault(p.getId(), List.of()), remindersByTask);
            projectNodes.add(new GetScheduleTreeResponse.ProjectNode(p.getId(), p.getName(),
                    p.getStatus().name(), p.getStartDate(), p.getEndDate(), milestoneNodes, taskNodes));
        }
        return new GetScheduleTreeResponse(projectNodes, toTaskNodes(standaloneTasks, remindersByTask));
    }

    /** SCHEDULE-AC-14 — 알림은 할 일 id 를 모아 한 번에 (네 번째 배치 조회). 할 일이 없으면 조회하지 않는다. */
    private Map<Long, List<Integer>> remindersByTask(List<Tasks> tasks) {
        Map<Long, List<Integer>> out = new HashMap<>();
        if (tasks.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>();
        for (Tasks t : tasks) {
            ids.add(t.getId());
        }
        for (TaskReminders r : reminderRepository.findAllForTasks(ids)) {
            out.computeIfAbsent(r.getTaskId(), k -> new ArrayList<>()).add(r.getMinutesBefore());
        }
        return out;
    }

    private List<GetScheduleTreeResponse.MilestoneNode> buildMilestoneNodes(
            List<Milestones> milestones,
            Map<Long, List<Milestones>> childMilestonesByParent,
            Map<Long, List<Tasks>> tasksByMilestone,
            Map<Long, List<Integer>> remindersByTask) {
        List<GetScheduleTreeResponse.MilestoneNode> nodes = new ArrayList<>();
        for (Milestones m : milestones) {
            List<GetScheduleTreeResponse.MilestoneNode> childNodes = buildMilestoneNodes(
                    childMilestonesByParent.getOrDefault(m.getId(), List.of()),
                    childMilestonesByParent, tasksByMilestone, remindersByTask);
            List<GetScheduleTreeResponse.TaskNode> taskNodes =
                    toTaskNodes(tasksByMilestone.getOrDefault(m.getId(), List.of()), remindersByTask);
            nodes.add(new GetScheduleTreeResponse.MilestoneNode(m.getId(), m.getName(),
                    m.getStatus().name(), m.getStartDate(), m.getEndDate(), childNodes, taskNodes));
        }
        return nodes;
    }

    private List<GetScheduleTreeResponse.TaskNode> toTaskNodes(List<Tasks> tasks,
                                                                Map<Long, List<Integer>> remindersByTask) {
        List<GetScheduleTreeResponse.TaskNode> nodes = new ArrayList<>();
        for (Tasks t : tasks) {
            nodes.add(new GetScheduleTreeResponse.TaskNode(t.getId(), t.getMilestoneId(), t.getName(),
                    t.getStatus().name(), t.getStartDate(), t.getEndDate(), t.getStartTime(), t.getEndTime(),
                    remindersByTask.getOrDefault(t.getId(), List.of()), t.getColor()));
        }
        return nodes;
    }

    /* ===== 프로젝트 ===== */

    @Override
    public ProjectResponse createProject(String loginId, PostProjectRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        validateDates(r.startDate(), r.endDate());
        Projects saved = projectRepository.save(
                Projects.create(memberId, r.name(), r.status(), r.startDate(), r.endDate()));
        return ProjectResponse.from(saved);
    }

    @Override
    public ProjectResponse updateProject(String loginId, Long id, PutProjectRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        validateDates(r.startDate(), r.endDate());
        Projects p = ownedProject(memberId, id);
        p.update(r.name(), r.status(), r.startDate(), r.endDate());
        return ProjectResponse.from(p);
    }

    @Override
    public void deleteProject(String loginId, Long id) {
        Long memberId = memberResolver.requireMemberId(loginId);
        Projects p = ownedProject(memberId, id);
        reminderRepository.deleteAllForProject(id);
        taskRepository.deleteAllForProject(id);
        milestoneRepository.deleteAllForProject(id);
        projectRepository.delete(p);
    }

    /* ===== 마일스톤 ===== */

    @Override
    public MilestoneResponse createMilestone(String loginId, PostMilestoneRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        validateDates(r.startDate(), r.endDate());
        Projects project = ownedProject(memberId, r.projectId());

        if (r.parentId() != null) {
            requireMilestoneInProject(r.parentId(), project.getId());
            Map<Long, Long> parents = parentIndex(project.getId());
            int depth = depthOf(r.parentId(), parents) + 1;
            if (depth > MAX_MILESTONE_DEPTH) {
                throw ScheduleErrorCode.DEPTH_EXCEEDED.asException();
            }
        }

        Milestones saved = milestoneRepository.save(
                Milestones.create(project.getId(), r.parentId(), r.name(), r.status(),
                        r.startDate(), r.endDate()));
        return MilestoneResponse.from(saved);
    }

    @Override
    public MilestoneResponse updateMilestone(String loginId, Long id, PutMilestoneRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        Milestones target = milestoneRepository.findById(id)
                .orElseThrow(ScheduleErrorCode.MILESTONE_NOT_FOUND::asException);
        Projects project = ownedProject(memberId, target.getProjectId());
        validateDates(r.startDate(), r.endDate());

        Long newParent = r.parentId();
        if (newParent != null) {
            if (newParent.equals(id)) {
                throw ScheduleErrorCode.CYCLE.asException();
            }
            requireMilestoneInProject(newParent, project.getId());
            Map<Long, Long> parents = parentIndex(project.getId());

            Long cursor = newParent;
            int steps = 0;
            while (cursor != null) {
                if (cursor.equals(id)) {
                    throw ScheduleErrorCode.CYCLE.asException();
                }
                cursor = parents.get(cursor);
                steps++;
                if (steps > parents.size() + 1) {
                    // 데이터가 이미 망가져 순환이면 무한 루프 대신 실패시킨다
                    throw ScheduleErrorCode.CYCLE.asException();
                }
            }

            int newDepth = depthOf(newParent, parents) + heightOf(id, parents);
            if (newDepth > MAX_MILESTONE_DEPTH) {
                throw ScheduleErrorCode.DEPTH_EXCEEDED.asException();
            }
        }

        target.update(r.parentId(), r.name(), r.status(), r.startDate(), r.endDate());
        return MilestoneResponse.from(target);
    }

    @Override
    public void deleteMilestone(String loginId, Long id) {
        Long memberId = memberResolver.requireMemberId(loginId);
        Milestones target = milestoneRepository.findById(id)
                .orElseThrow(ScheduleErrorCode.MILESTONE_NOT_FOUND::asException);
        ownedProject(memberId, target.getProjectId());

        List<Long> ids = milestoneRepository.findSelfAndDescendantIds(id);
        reminderRepository.deleteAllForMilestones(ids);
        taskRepository.deleteAllForMilestones(ids);
        milestoneRepository.deleteAllByIds(ids);
    }

    /* ===== 할 일 ===== */

    @Override
    public TaskResponse createTask(String loginId, PostTaskRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        validateTimes(r.startDate(), r.endDate(), r.startTime(), r.endTime());
        List<Integer> reminders = validateReminders(r.reminders(), r.startDate(), r.startTime());

        // projectId 가 없으면 무소속 — 마일스톤 소속은 불가능하다 (SCHEDULE-AC-31)
        if (r.projectId() == null) {
            if (r.milestoneId() != null) {
                throw ScheduleErrorCode.CROSS_PROJECT.asException();
            }
            Tasks saved = taskRepository.save(
                    Tasks.create(memberId, null, null, r.name(), r.status(),
                            r.startDate(), r.endDate(), r.startTime(), r.endTime(), ScheduleColors.randomColor()));
            saveReminders(saved.getId(), reminders);
            return TaskResponse.from(saved, reminders);
        }

        Projects project = ownedProject(memberId, r.projectId());
        if (r.milestoneId() != null) {
            requireMilestoneInProject(r.milestoneId(), project.getId());
        }

        Tasks saved = taskRepository.save(
                Tasks.create(memberId, project.getId(), r.milestoneId(), r.name(), r.status(),
                        r.startDate(), r.endDate(), r.startTime(), r.endTime(), ScheduleColors.randomColor()));
        saveReminders(saved.getId(), reminders);
        return TaskResponse.from(saved, reminders);
    }

    @Override
    public TaskResponse updateTask(String loginId, Long id, PutTaskRequest r) {
        Long memberId = memberResolver.requireMemberId(loginId);
        Tasks target = ownedTask(memberId, id);
        validateTimes(r.startDate(), r.endDate(), r.startTime(), r.endTime());
        List<Integer> reminders = validateReminders(r.reminders(), r.startDate(), r.startTime());

        String color = r.color();
        if (color != null) {
            if (!ScheduleColors.isValidHex(color)) {
                throw ScheduleErrorCode.INVALID_COLOR.asException();
            }
        } else {
            color = target.getColor();
        }

        if (r.milestoneId() != null) {
            // 무소속 할 일은 마일스톤에 붙을 수 없다
            if (target.getProjectId() == null) {
                throw ScheduleErrorCode.CROSS_PROJECT.asException();
            }
            requireMilestoneInProject(r.milestoneId(), target.getProjectId());
        }

        LocalDateTime previousStartAt = target.startAt();
        target.update(r.milestoneId(), r.name(), r.status(), r.startDate(), r.endDate(),
                r.startTime(), r.endTime(), color);
        List<Integer> saved = replaceRemindersIfChanged(target, previousStartAt, reminders);
        return TaskResponse.from(target, saved);
    }

    @Override
    public void deleteTask(String loginId, Long id) {
        Long memberId = memberResolver.requireMemberId(loginId);
        Tasks target = ownedTask(memberId, id);
        reminderRepository.deleteAllForTask(id);
        taskRepository.delete(target);
    }

    /** 남의(또는 없는) 할 일은 같은 404 로 — 소유권은 할 일 자신의 memberId 로 판별한다 (AC-31). */
    private Tasks ownedTask(Long memberId, Long taskId) {
        return taskRepository.findById(taskId)
                .filter(t -> t.getMemberId().equals(memberId))
                .orElseThrow(ScheduleErrorCode.TASK_NOT_FOUND::asException);
    }

    /* ===== 알림 설정 ===== */

    static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getNotification(String loginId) {
        return new NotificationResponse(memberResolver.requireMember(loginId).getSlackWebhookUrl());
    }

    /** SCHEDULE-AC-24/25 — Slack Incoming Webhook 만 허용한다. */
    @Override
    public NotificationResponse updateNotification(String loginId, PutNotificationRequest r) {
        Member member = memberResolver.requireMember(loginId);
        if (r.webhookUrl() == null || !r.webhookUrl().startsWith(SLACK_WEBHOOK_PREFIX)) {
            throw ScheduleErrorCode.INVALID_WEBHOOK_URL.asException();
        }
        member.changeSlackWebhookUrl(r.webhookUrl());
        return new NotificationResponse(member.getSlackWebhookUrl());
    }

    @Override
    public void deleteNotification(String loginId) {
        memberResolver.requireMember(loginId).removeSlackWebhookUrl();
    }
}
