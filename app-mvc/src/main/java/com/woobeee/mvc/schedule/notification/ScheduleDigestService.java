package com.woobeee.mvc.schedule.notification;

import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 멤버 한 명 분량의 다이제스트를 만들고, 기한 경과 항목을 완료로 정산한다.
 * 발송(HTTP)은 트랜잭션 밖의 {@link ScheduleSlackNotifier}가 한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ScheduleDigestService {

    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final TaskRepository taskRepository;

    /** 보낼 것이 없으면 empty. SCHEDULE-AC-28: 기한 경과 목록을 수집한 뒤에 자동 완료를 실행한다. */
    public Optional<String> buildAndSettleDigest(Long memberId) {
        List<Projects> projects = projectRepository.findAllForMember(memberId);
        if (projects.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, String> projectNames = new HashMap<>();
        for (Projects p : projects) {
            projectNames.put(p.getId(), p.getName());
        }

        List<Tasks> dueToday = taskRepository.findDueTodayForMember(memberId);
        List<Tasks> startingToday = taskRepository.findStartingTodayForMember(memberId);
        List<Tasks> overdue = taskRepository.findOverdueForMember(memberId);

        Optional<String> digest = SlackDigestFormatter.build(
                LocalDate.now(), dueToday, startingToday, overdue, projectNames);

        // 수집이 끝났으니 기한 경과를 세 층 모두 완료로 (getTree 의 자동 완료와 같은 규칙)
        projectRepository.completeOverdueForMember(memberId);
        milestoneRepository.completeOverdueForMember(memberId);
        taskRepository.completeOverdueForMember(memberId);

        return digest;
    }
}
