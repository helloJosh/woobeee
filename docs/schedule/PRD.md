# schedule PRD — 일정 관리

## 개요

로그인한 유저가 **자기 일정만** 관리한다. 구조는 프로젝트 > 마일스톤(재귀, 깊이 ≤ 5) > 할 일.
할 일은 프로젝트 직속 또는 아무 깊이의 마일스톤 아래에 붙는다. 상태(시작전/진행중/완료)와
날짜 범위(종료 미정 허용)는 세 층 모두 각자 가진다. 할 일은 고유색을 가지고 달력에 표시된다.

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
| SCHEDULE-AC-09 | 할 일 생성 | 12색 팔레트 중 하나가 자동 배정되어 응답에 포함 |
| SCHEDULE-AC-10 | `#RRGGBB` 형식이 아닌 색으로 수정 | 400 + `schedule_invalidColor` |
| SCHEDULE-AC-11 | `endDate < startDate` | 400 + `schedule_invalidDateRange` |
| SCHEDULE-AC-12 | 프로젝트 삭제 | 하위 마일스톤·할 일 전부 함께 삭제, 한 트랜잭션 |
| SCHEDULE-AC-13 | 마일스톤 삭제 | 자기+자손 마일스톤과 거기 달린 할 일 전부 삭제 |
| SCHEDULE-AC-14 | `GET /tree`의 저장소 접근 | 조회 3회(프로젝트/마일스톤/할 일), 루프 내 단건 조회 없음 |
| SCHEDULE-AC-15 | `ScheduleErrorCode` ↔ `error-messages.ts` | ko/en 모두 양방향 일치 (없는 코드도, 죽은 키도 실패) |
| SCHEDULE-AC-16 | bean validation 실패(빈 name 등)·깨진 JSON | 400 + `schedule_badRequest` 봉투 |
| SCHEDULE-AC-17 | `filterTree(tree, status)` | 자기 상태가 일치하는 노드와 그 조상 체인만 남고, 조상은 `dimmed` 표시 |
| SCHEDULE-AC-18 | `calendarLayout` 월 경계 | 월 밖 구간은 잘리고 `continuesLeft`/`continuesRight`로 이어짐을 표시 |
| SCHEDULE-AC-19 | `calendarLayout` 종료 미정 할 일 | 시작일부터 월 말까지 깔리고 `openEnded` 플래그 |
| SCHEDULE-AC-20 | `formatDateRange` | 미정은 `미정`, 올해 날짜는 연도 생략(`08.20`), 다른 해는 `25.12.31` |
