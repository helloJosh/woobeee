package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.schedule.api.request.PostMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PostProjectRequest;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.api.request.PutMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PutProjectRequest;
import com.woobeee.mvc.schedule.api.request.PutTaskRequest;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.api.response.MilestoneResponse;
import com.woobeee.mvc.schedule.api.response.ProjectResponse;
import com.woobeee.mvc.schedule.api.response.TaskResponse;

public interface ScheduleService {

    GetScheduleTreeResponse getTree(String loginId);

    ProjectResponse createProject(String loginId, PostProjectRequest request);

    ProjectResponse updateProject(String loginId, Long id, PutProjectRequest request);

    void deleteProject(String loginId, Long id);

    MilestoneResponse createMilestone(String loginId, PostMilestoneRequest request);

    MilestoneResponse updateMilestone(String loginId, Long id, PutMilestoneRequest request);

    void deleteMilestone(String loginId, Long id);

    TaskResponse createTask(String loginId, PostTaskRequest request);

    TaskResponse updateTask(String loginId, Long id, PutTaskRequest request);

    void deleteTask(String loginId, Long id);
}
