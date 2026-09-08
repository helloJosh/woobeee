# schedule PRD — 일정 관리

## 개요

로그인한 유저가 **자기 일정만** 관리한다. 구조는 프로젝트 > 마일스톤(재귀, 깊이 ≤ 5) > 할 일.
할 일은 프로젝트 직속 또는 아무 깊이의 마일스톤 아래에 붙는다. 상태(시작전/진행중/완료/보류/오류)와
날짜 범위(종료 미정 허용)는 세 층 모두 각자 가진다. 할 일은 고유색을 가지고 달력에 표시된다.
할 일 밑에는 **이슈사항**(내용 + 해결 여부)을 달 수 있다 — 달력에는 나오지 않고 할 일 행 아래에
접혀 있다가 「이슈 N」 배지로 펼친다.

- 백엔드: app-mvc `com.woobeee.mvc.schedule`, 베이스 경로 `/api/back/schedule`
- 프론트: `/schedule` — 트리 리스트(위) + 월 달력(아래), 상태 필터는 둘 다 적용
- DB FK 제약 없음: 참조 무결성은 서비스 계층이 검증한다
- 설계 근거: `docs/superpowers/specs/2026-09-01-schedule-tab-design.md`

## 인수 기준 (Acceptance Criteria)

| ID | 시나리오 | 기대 결과 |
| --- | --- | --- |
| SCHEDULE-AC-01 | 토큰 없이(또는 무효 토큰으로) `/api/back/schedule/*` 호출 | 401 + `schedule_unauthorized` 봉투 |
| SCHEDULE-AC-02 | `GET /tree` | 본인 `member_id` 프로젝트만, 프로젝트>마일스톤(재귀)>할 일 중첩 구조로 반환 |
| SCHEDULE-AC-03 | 남의 프로젝트(또는 그 하위)에 수정/삭제/생성 시도 | 404 + `schedule_projectNotFound` — 존재 여부를 흘리지 않는다 |
| SCHEDULE-AC-04 | 프로젝트 생성 시 status 생략 | `NOT_STARTED`로 저장 |
| SCHEDULE-AC-05 | 존재하지 않는 `projectId`/`parentId`/`milestoneId` 참조 | 404 + `schedule_projectNotFound`/`schedule_milestoneNotFound` |
| SCHEDULE-AC-06 | `parentId`·`milestoneId`가 요청의 프로젝트와 다른 프로젝트 소속 | 400 + `schedule_crossProject` |
| SCHEDULE-AC-07 | 마일스톤 깊이 5 초과 생성/이동 | 400 + `schedule_depthExceeded` |
| SCHEDULE-AC-08 | 마일스톤을 자기 자신/자기 자손 아래로 이동 | 400 + `schedule_cycle` |
| SCHEDULE-AC-09 | 할 일 생성 | `color` 를 실으면 그 색(형식이 틀리면 AC-10 과 같은 `schedule_invalidColor`), 생략하면 24색 팔레트 중 하나가 자동 배정되어 응답에 포함. 생성 다이얼로그는 `pickColor` 로 팔레트에서 미리 고른 색을 보여 주고 바꿀 수 있다 |
| SCHEDULE-AC-10 | `#RRGGBB` 형식이 아닌 색으로 수정 | 400 + `schedule_invalidColor` |
| SCHEDULE-AC-11 | `endDate < startDate` | 400 + `schedule_invalidDateRange` |
| SCHEDULE-AC-12 | 프로젝트 삭제 | 하위 마일스톤·할 일 전부 함께 삭제, 한 트랜잭션 |
| SCHEDULE-AC-13 | 마일스톤 삭제 | 자기+자손 마일스톤과 거기 달린 할 일 전부 삭제 |
| SCHEDULE-AC-14 | `GET /tree`의 저장소 접근 | 배치 조회 4회(프로젝트/마일스톤/할 일/알림 — 할 일이 없으면 알림 조회 생략), 루프 내 단건 조회 없음 |
| SCHEDULE-AC-15 | `ScheduleErrorCode` ↔ `error-messages.ts` | ko/en 모두 양방향 일치 (없는 코드도, 죽은 키도 실패) |
| SCHEDULE-AC-16 | bean validation 실패(빈 name 등)·깨진 JSON | 400 + `schedule_badRequest` 봉투 |
| SCHEDULE-AC-17 | `filterTree(tree, status)` | 자기 상태가 일치하는 노드와 그 조상 체인만 남고, 조상은 `dimmed` 표시 |
| SCHEDULE-AC-18 | `calendarLayout` 월 경계 | 월 밖 구간은 잘리고 `continuesLeft`/`continuesRight`로 이어짐을 표시 |
| SCHEDULE-AC-19 | `calendarLayout` 종료 미정 항목(세 종류 공통) | **시작일 하루만** 표시(`openEnded` — → 표식). 시작일이 그리드 밖인 달에서는 표시되지 않는다. 완료 항목의 막대는 취소선 |
| SCHEDULE-AC-20 | `formatDateRange` | 미정은 `미정`, 올해 날짜는 연도 생략(`08.20`), 다른 해는 `25.12.31` |
| SCHEDULE-AC-21 | 종료일이 지난(어제 이전) 항목이 있을 때 `GET /tree` | 프로젝트·마일스톤·할 일 모두 조회 전에 상태가 `DONE`으로 자동 갱신되어 반환 |
| SCHEDULE-AC-22 | 종료일 미정(NULL), 종료일이 오늘 당일, 또는 **마감이 지난 뒤 사용자가 직접 수정한 항목**(updated_at > 종료일 — 상태 배지 클릭 포함) | 자동 완료 대상에서 제외 — 수동 변경이 자동 완료를 이긴다 |
| SCHEDULE-AC-23 | 할 일 생성 다이얼로그 | 시작일 기본값이 오늘(`todayIso()`, 수정 가능). 프로젝트·마일스톤 생성은 빈 값 유지 |
| SCHEDULE-AC-24 | `PUT /notification`에 `https://hooks.slack.com/`으로 시작하지 않는 URL | 400 + `schedule_invalidWebhookUrl` |
| SCHEDULE-AC-25 | Slack webhook 등록/조회/해제 (`GET`/`PUT`/`DELETE /notification`) | 본인 `members.slack_webhook_url`만 읽고 쓴다. 미등록 조회는 `webhookUrl: null` |
| SCHEDULE-AC-26 | 매일 09:00(Asia/Seoul) 다이제스트 | webhook 등록 멤버에게 오늘 마감·오늘 시작·기한 경과(자동 완료 대상 — AC-22 예외 동일 적용) **할 일**을 한 메시지로 발송. 세 목록이 모두 비면 발송하지 않는다 |
| SCHEDULE-AC-27 | 다이제스트 발송 중 한 멤버의 webhook 호출 실패 | 로그만 남기고 다음 멤버 발송을 계속한다 |
| SCHEDULE-AC-28 | 다이제스트의 기한 경과 목록 | 목록을 수집한 **뒤** 세 층 자동 완료(`completeOverdueForMember`)를 실행한다 — 알림에는 완료 전 상태로 담긴다 |
| SCHEDULE-AC-29 | 트리에서 상태 배지 클릭 | `nextStatus`: 시작전→진행중→완료→시작전 순환. 해당 노드만 옵티미스틱으로 즉시 반영, 저장 실패 시에만 재조회로 원복 |
| SCHEDULE-AC-30 | 달력 계층 표시 | 프로젝트·마일스톤도 날짜 범위만큼 막대로 표시(`collectCalendarEntries` — 트리 순서). 같은 프로젝트 항목은 연속 lane 에 붙고(`laneGroupStarts`), 프로젝트 그룹 사이만 간격. 주 높이는 막대 수에 맞춰 동적이며 막대를 숨기지 않는다 |
| SCHEDULE-AC-31 | 무소속 할 일 | `POST /tasks`에 `projectId` 생략 시 어느 프로젝트에도 속하지 않는 할 일로 저장(`tasks.member_id`로 직접 소유 — V10). 마일스톤 소속 불가(`schedule_crossProject`). 트리 응답 최상위 `tasks`, 화면은 맨 아래 「바로 할 일」 섹션·달력 마지막 그룹. 자동 완료·다이제스트 동일 적용(다이제스트에선 프로젝트 접두 없이) |
| SCHEDULE-AC-32 | 달력에서 생성 | 빈 날짜 칸 클릭=그 날짜 하루짜리, 드래그=범위(`orderedRange` — 역방향 허용, 앞뒤 달 칸도 실제 날짜)로 **무소속** 할 일 생성 다이얼로그. 막대 위에서는 생성이 시작되지 않는다(막대 드래그는 AC-33 이동, 움직임 없는 클릭은 수정) |
| SCHEDULE-AC-33 | 달력 막대 드래그 | 프로젝트·마일스톤·할 일 막대 몸통을 끌면 기간 전체가 놓은 칸까지의 일수만큼 이동(`draggedRange` move — null 날짜는 null 유지). 양끝 손잡이를 끌면 그 끝만 이동(resize-start/resize-end)하고, 끝이 서로를 지나치면 하루짜리로 고정. 손잡이는 시작·종료일이 둘 다 있는 막대에만. 끄는 동안 새 위치에 미리 그려지고(`applyBarDrag`), 놓는 즉시 이름·상태 그대로 날짜만 저장, 실패 시 원위치. Esc 로 취소 |
| SCHEDULE-AC-34 | 할 일 시간 입력 | `startTime`/`endTime`(`HH:mm`, 선택)을 `tasks.start_time`/`end_time`(TIME, V11)에 저장·응답. 해당 날짜가 없으면 시간은 null 로 정규화. 같은 날짜에 둘 다 있고 `endTime < startTime` 이면 400 + `schedule_invalidDateRange`. 날짜 컬럼과 달력·자동 완료·다이제스트 로직은 그대로. 트리 행·달력 막대 라벨에 시간이 있으면 표시(`formatTaskRange`) |
| SCHEDULE-AC-35 | 시작 전 알림 저장 | `reminders: number[]` — 10·30 만(아니면 400 + `schedule_invalidReminder`), 비어 있지 않은데 `startDate` 나 `startTime` 이 없으면 400 + `schedule_reminderNeedsStartTime`. `task_reminders(task_id, minutes_before, sent_at)` 에 한 행씩. `PUT` 은 집합 교체 — 시작 일시와 집합이 모두 같으면 행을 건드리지 않고(보낸 기록 유지), 하나라도 다르면 삭제 후 재생성. 할 일·마일스톤·프로젝트 삭제 시 알림 행부터 지운다 |
| SCHEDULE-AC-36 | 시작 전 알림 발송 | `TaskReminderNotifier` 가 매 분(Asia/Seoul 기준 `now`) 미발송·webhook 등록·발송 시각 도달·**아직 시작 전**인 알림을 한 쿼리로 가져와 Slack 으로 보내고 성공한 건만 `sent_at` 을 찍는다. 실패는 로그만, 다음 분 재시도, 시작 시각을 넘기면 조회에서 빠진다(놓친 알림은 뒤늦게 보내지 않음). 본문 `⏰ 30분 후 시작 (14:30) — [프로젝트] 이름`, 무소속은 접두 없이 |
| SCHEDULE-AC-37 | 날짜만 고치는 경로의 보존 | 막대 팝오버·막대 드래그·배지 클릭은 `taskPutBody(task, patch)` 로 기존 시간·알림·소속·색을 실어 보낸다. 날짜를 비우면 그쪽 시간을 비우고, 시작 일시가 사라지면 알림도 비운다 |
| SCHEDULE-AC-38 | 상태 보류·오류 | `ScheduleStatus` 에 `ON_HOLD`(보류)·`ERROR`(오류) — 세 층 공통, 세 테이블 CHECK 제약 5값(V12). 상태 필터 탭·수정 다이얼로그 선택지에 포함. 배지 클릭 순환(`nextStatus`)은 시작전→진행중→완료→보류→오류→시작전. 달력 막대는 보류는 흐리게(점선 외곽), 오류는 붉은 테두리 — 세그먼트가 `status` 를 실어 나른다 |
| SCHEDULE-AC-39 | 보류·오류와 자동 완료 | 기한이 지나도 `ON_HOLD`·`ERROR` 는 자동 완료(AC-21)·다이제스트 기한 경과 목록(AC-26/28)에서 제외 — 네 쿼리 모두 `status NOT IN ('DONE','ON_HOLD','ERROR')`. 사용자가 세워 둔 상태를 기계가 덮지 않는다 |
| SCHEDULE-AC-40 | 이슈 CRUD | `POST /tasks/{taskId}/issues`(content, ≤1000자, 빈 값은 `schedule_badRequest`) → 미해결로 생성. `PUT /issues/{issueId}`(content, resolved) 전체 교체. `DELETE /issues/{issueId}`. 소유권은 부모 할 일의 `member_id` — 남의 할 일(또는 그 이슈)은 404 `schedule_taskNotFound`, 없는 이슈는 404 `schedule_issueNotFound` |
| SCHEDULE-AC-41 | 트리의 이슈 | `GET /tree` 의 각 `TaskNode.issues: [{id, taskId, content, resolved}]`(id 순). 프론트는 `normalizeTree` 로 `issues` 가 빠진 응답(재시작 전 구버전 서버)에 빈 배열을 채워 화면이 깨지지 않게 하고, 이슈 패널은 저장 실패를 삼키지 않고 패널 아래에 문구로 드러낸다. 배치 조회 5회(프로젝트/마일스톤/할 일/알림/이슈 — 할 일이 없으면 뒤 둘 생략), 루프 내 단건 조회 없음. 할 일·마일스톤·프로젝트 삭제 시 `task_issues` 를 알림보다 먼저(tasks 삭제 전에) 지운다 |
| SCHEDULE-AC-42 | 이슈 화면 | 할 일 행 **왼쪽의 `>`/`v` 화살표**(마일스톤과 같은 자리)로 행 아래 이슈 패널을 접고 펼친다(기본 접힘). 이름 옆 「이슈 N/M」 배지는 표시 전용(미해결/전체 — 미해결 있으면 주황, 전부 해결이면 회색, 없으면 생략). 체크=해결(`applyIssue` 옵티미스틱, 실패 시 재조회), 내용 클릭=수정, 삭제, 한 줄 입력=추가. 이슈는 달력·`collectCalendarEntries`·상태 필터에 관여하지 않는다 |
