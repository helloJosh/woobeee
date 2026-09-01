package com.woobeee.mvc.schedule.controller;

import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.exception.ScheduleControllerAdvice;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    @Mock ScheduleService scheduleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduleController(scheduleService))
                .setControllerAdvice(new ScheduleControllerAdvice())
                .build();
    }

    /** SCHEDULE-AC-02 — 트리가 봉투에 담겨 나간다. */
    @Test
    void treeIsWrappedInTheEnvelope() throws Exception {
        when(scheduleService.getTree("me@example.com"))
                .thenReturn(new GetScheduleTreeResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/back/schedule/tree").header("loginId", "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.projects").isArray());
    }

    /** SCHEDULE-AC-01 — 필터가 loginId 를 못 심으면 401 + 코드 키 봉투. */
    @Test
    void aMissingLoginIdBecomesTheUnauthorizedEnvelope() throws Exception {
        when(scheduleService.getTree(isNull()))
                .thenThrow(ScheduleErrorCode.UNAUTHORIZED.asException());

        mockMvc.perform(get("/api/back/schedule/tree"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.message").value("schedule_unauthorized"));
    }

    /** SCHEDULE-AC-16 — bean validation 실패는 schedule_badRequest 봉투. */
    @Test
    void aBlankNameBecomesTheBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/back/schedule/projects")
                        .header("loginId", "me@example.com")
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("schedule_badRequest"));
    }

    /** SCHEDULE-AC-03 — 서비스의 PROJECT_NOT_FOUND 가 404 봉투로 나간다. */
    @Test
    void projectNotFoundBecomesA404Envelope() throws Exception {
        when(scheduleService.getTree("me@example.com"))
                .thenThrow(ScheduleErrorCode.PROJECT_NOT_FOUND.asException());

        mockMvc.perform(get("/api/back/schedule/tree").header("loginId", "me@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("schedule_projectNotFound"));
    }

    /** 경로변수 타입 불일치(MethodArgumentTypeMismatchException) 도 schedule_badRequest 봉투로 나간다. */
    @Test
    void aNonNumericPathVariableBecomesTheBadRequestEnvelope() throws Exception {
        mockMvc.perform(put("/api/back/schedule/tasks/abc")
                        .header("loginId", "me@example.com")
                        .contentType("application/json")
                        .content("{\"name\":\"t\",\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("schedule_badRequest"));
    }

    /** SCHEDULE-AC-24 — 서비스의 INVALID_WEBHOOK_URL 이 400 봉투로 나간다. */
    @Test
    void anInvalidWebhookUrlBecomesA400Envelope() throws Exception {
        when(scheduleService.updateNotification(eq("me@example.com"), any()))
                .thenThrow(ScheduleErrorCode.INVALID_WEBHOOK_URL.asException());

        mockMvc.perform(put("/api/back/schedule/notification")
                        .header("loginId", "me@example.com")
                        .contentType("application/json")
                        .content("{\"webhookUrl\":\"https://example.com/hook\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("schedule_invalidWebhookUrl"));
    }
}
