package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleDigestServiceTest {

    private static final long MEMBER_ID = 7L;

    @Mock ProjectRepository projectRepository;
    @Mock MilestoneRepository milestoneRepository;
    @Mock TaskRepository taskRepository;

    @InjectMocks ScheduleDigestService digestService;

    private Projects project(long id) {
        Projects p = Projects.create(MEMBER_ID, "DM", ScheduleStatus.IN_PROGRESS, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** SCHEDULE-AC-28 — 기한 경과 목록을 수집한 뒤에 자동 완료를 실행한다. */
    @Test
    void overdueIsCollectedBeforeSettling() {
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(project(10L)));
        when(taskRepository.findDueTodayForMember(MEMBER_ID)).thenReturn(List.of());
        when(taskRepository.findStartingTodayForMember(MEMBER_ID)).thenReturn(List.of());
        when(taskRepository.findOverdueForMember(MEMBER_ID)).thenReturn(List.of(
                Tasks.create(MEMBER_ID, 10L, null, "밀린 일", ScheduleStatus.IN_PROGRESS, null, null, "#ef4444")));

        Optional<String> digest = digestService.buildAndSettleDigest(MEMBER_ID);

        assertThat(digest).isPresent();
        assertThat(digest.get()).contains("• [DM] 밀린 일");
        InOrder order = inOrder(taskRepository, projectRepository, milestoneRepository);
        order.verify(taskRepository).findOverdueForMember(MEMBER_ID);
        order.verify(projectRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(milestoneRepository).completeOverdueForMember(MEMBER_ID);
        order.verify(taskRepository).completeOverdueForMember(MEMBER_ID);
    }

    /** SCHEDULE-AC-31 — 프로젝트가 없어도 무소속 할 일이 있을 수 있어 조회·정산은 수행한다. */
    @Test
    void aMemberWithoutProjectsStillDigestsStandaloneTasks() {
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of());
        when(taskRepository.findDueTodayForMember(MEMBER_ID)).thenReturn(List.of(
                Tasks.create(MEMBER_ID, null, null, "장보기", ScheduleStatus.NOT_STARTED, null, null, "#ef4444")));
        when(taskRepository.findStartingTodayForMember(MEMBER_ID)).thenReturn(List.of());
        when(taskRepository.findOverdueForMember(MEMBER_ID)).thenReturn(List.of());

        Optional<String> digest = digestService.buildAndSettleDigest(MEMBER_ID);

        assertThat(digest).isPresent();
        assertThat(digest.get()).contains("• 장보기");
        verify(taskRepository).completeOverdueForMember(MEMBER_ID);
    }
}
