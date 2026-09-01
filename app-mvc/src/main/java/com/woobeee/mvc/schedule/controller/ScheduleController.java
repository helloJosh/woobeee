package com.woobeee.mvc.schedule.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.schedule.api.request.*;
import com.woobeee.mvc.schedule.api.response.*;
import com.woobeee.mvc.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/back/schedule")
@Tag(name = "Schedule Controller", description = "일정 관리 컨트롤러")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService scheduleService;

    @GetMapping("/tree")
    @Operation(summary = "일정 트리 조회", description = "내 프로젝트>마일스톤>할 일 전체 트리를 조회합니다.")
    public ApiResponse<GetScheduleTreeResponse> getTree(
            @RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(scheduleService.getTree(loginId), "Schedule tree retrieved");
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "프로젝트 생성")
    public ApiResponse<ProjectResponse> createProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostProjectRequest request) {
        return ApiResponse.createSuccess(scheduleService.createProject(loginId, request), "Project created");
    }

    @PutMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 수정")
    public ApiResponse<ProjectResponse> updateProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long projectId,
            @Valid @RequestBody PutProjectRequest request) {
        return ApiResponse.success(scheduleService.updateProject(loginId, projectId, request), "Project updated");
    }

    @DeleteMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 삭제", description = "하위 마일스톤·할 일을 함께 삭제합니다.")
    public ApiResponse<Void> deleteProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long projectId) {
        scheduleService.deleteProject(loginId, projectId);
        return ApiResponse.success("Project deleted");
    }

    @PostMapping("/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "마일스톤 생성")
    public ApiResponse<MilestoneResponse> createMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostMilestoneRequest request) {
        return ApiResponse.createSuccess(scheduleService.createMilestone(loginId, request), "Milestone created");
    }

    @PutMapping("/milestones/{milestoneId}")
    @Operation(summary = "마일스톤 수정")
    public ApiResponse<MilestoneResponse> updateMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody PutMilestoneRequest request) {
        return ApiResponse.success(scheduleService.updateMilestone(loginId, milestoneId, request), "Milestone updated");
    }

    @DeleteMapping("/milestones/{milestoneId}")
    @Operation(summary = "마일스톤 삭제", description = "자손 마일스톤·할 일을 함께 삭제합니다.")
    public ApiResponse<Void> deleteMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long milestoneId) {
        scheduleService.deleteMilestone(loginId, milestoneId);
        return ApiResponse.success("Milestone deleted");
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "할 일 생성", description = "색은 서버가 팔레트에서 자동 배정합니다.")
    public ApiResponse<TaskResponse> createTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostTaskRequest request) {
        return ApiResponse.createSuccess(scheduleService.createTask(loginId, request), "Task created");
    }

    @PutMapping("/tasks/{taskId}")
    @Operation(summary = "할 일 수정")
    public ApiResponse<TaskResponse> updateTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long taskId,
            @Valid @RequestBody PutTaskRequest request) {
        return ApiResponse.success(scheduleService.updateTask(loginId, taskId, request), "Task updated");
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "할 일 삭제")
    public ApiResponse<Void> deleteTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long taskId) {
        scheduleService.deleteTask(loginId, taskId);
        return ApiResponse.success("Task deleted");
    }
}
