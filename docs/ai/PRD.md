# ai PRD — AI 요청하기

## 개요

웹의 "AI 요청하기"에 글과 이미지(예: App Store 상품 목록 스크린샷)를 올리면, 별도 컨테이너(`ai-worker`)의
Claude Code(`claude -p`)가 MCP 도구로 **공동구매 차수 상품**과 **일정(할 일)** 을 *제안*한다. 사용자가
웹에서 제안을 확인·승인하면 그때 등록된다. 설계 근거는
`docs/superpowers/specs/2026-11-06-ai-request-mcp-design.md`, 결정은 `docs/_global/adr/ADR-005-kafka-ai-requests.md`.

- 백엔드: app-mvc `com.woobeee.mvc.ai`, 사용자 API `/api/back/ai`, 워커 전용 내부 API `/internal/ai`
- 워커: `ai-worker/` (Node/TypeScript). Kafka `ai.request.v1` 를 소비하고, MCP 서버(stdio)를 `claude` 의 자식 프로세스로 띄운다
- 전달은 at-least-once, 소비는 멱등이다. 발행 상태는 DB(`ai_outbox`)가 들고 있다
- AI 는 제안만 한다. 반영은 승인 시점에 app-mvc 가 **요청자 권한으로 기존 서비스**를 호출해 한다
- 인증은 구독 로그인 토큰(`CLAUDE_CODE_OAUTH_TOKEN`), 사용자는 본인 1명. 작업은 한 번에 하나(직렬)
- 판단 로직은 `front/lib/ai.ts` 에 두고 `lib/ai.test.ts` 가 고정한다

## 인수 기준 (Acceptance Criteria)

| ID | 시나리오 | 기대 결과 |
| --- | --- | --- |
| AI-AC-01 | 요청 접수 | 로그인 필수(없으면 401 `ai_loginRequired`). 글·이미지 모두 비면 400 `ai_emptyRequest`. 이미지는 ≤ 5장·장당 ≤ 5 MB·png/jpeg/webp 만(위반은 400, 업로드·저장 전에 거절). 요청(`RECEIVED`)과 outbox(`PENDING`)는 **한 트랜잭션**으로 저장된다 |
| AI-AC-02 | 사용자당 진행 중 요청 1건 | 진행 중(`RECEIVED`·`PROCESSING`·`FAILED`)인 요청이 있으면 새 요청은 409 `ai_requestInProgress`(업로드·저장 전에 거절). 동시에 두 번 제출해도 DB 부분 유니크 인덱스가 하나만 허용하고, `DEAD` 재시도도 같은 제한을 받는다. `DRAFTED`·`APPLIED`·`DEAD` 는 진행 중이 아니다 |
| AI-AC-03 | 발행(아웃박스) | 발행기가 `PENDING` 행을 `ai.request.v1`(키 = `requestId`)로 보내고 `PUBLISHED` 로 바꾼다. 실패하면 지수 백오프(`min(2^n × 5초, 10분)`)로 다시 시도하고, 8회를 넘으면 outbox `DEAD`·요청 `FAILED`(`publish_failed`). Kafka 가 죽어 있어도 접수는 성공한다 |
| AI-AC-04 | 발행 중복·동시성 | 같은 이벤트가 두 번 나갈 수 있다(소비 측이 받아낸다). 발행기 둘이 떠도 `FOR UPDATE SKIP LOCKED` 로 같은 행을 잡지 않는다 |
| AI-AC-05 | claim | `RECEIVED`, 또는 리스가 만료된 `PROCESSING`·`FAILED` 만 CAS 로 `PROCESSING` 이 된다(`attempts+1`, 리스 5분). 이미 `DRAFTED`·`APPLIED`·`DEAD` 이거나 리스가 살아 있으면 409 `ai_notClaimable` |
| AI-AC-06 | attempt 펜싱 | complete·fail·제안·읽기 호출은 claim 응답의 `attempt` 가 현재 `attempts` 와 같을 때만 받는다. 리스 만료 뒤 늦게 온 이전 워커의 보고는 409 `ai_staleAttempt` |
| AI-AC-07 | 완료·제안 기록 | 제안은 `ai_request_actions` 에 `PENDING` 으로만 쌓이고(`UNIQUE(request_id, action_key)` 로 중복 제안은 무시) 아무것도 등록되지 않는다. 제안이 0건이어도 complete 하면 `DRAFTED` 다 |
| AI-AC-08 | 실패·재처리 | 워커가 fail 을 보고하면 처리 횟수 3회 미만은 `FAILED`(재시도 가능 시각 = `lease_until`)로 두고 같은 토픽으로 재발행한다. 3회째는 `DEAD` 로 두고 `ai.request.dlq` 에 기록 이벤트를 남긴다(DLQ 발행 실패는 삼킨다) |
| AI-AC-09 | 리스 스위퍼 | 리스가 만료된 `PROCESSING` 은 스위퍼가 `lease_expired` 로 실패 처리하고 AI-AC-08 경로로 재발행한다. 리스가 살아 있는 것은 건드리지 않는다 |
| AI-AC-10 | 수동 재시도 | `DEAD` 만 `POST /retry` 로 다시 돌린다(그 외 409 `ai_notRetryable`). 요청자 본인 또는 전역 운영자만이고, 남에게는 404 다. `attempts` 가 0 으로 리셋되고 새 outbox 가 쌓인다 |
| AI-AC-11 | 승인 멱등 | apply 는 `PENDING → APPLIED` CAS 가 성공한 요청만 실제로 등록한다. 두 번 눌러도 한 번만 반영되고 두 번째는 409 `ai_actionAlreadyDone`. 모든 제안이 `APPLIED`·`REJECTED` 가 되면 요청은 `APPLIED` 가 된다 |
| AI-AC-12 | 승인 권한 위반 | 기존 서비스가 권한 예외를 던지면 apply 는 403 `ai_applyForbidden` 이고 **제안은 `PENDING` 으로 남는다**(CAS 가 함께 롤백). 요청 상태도 그대로다 |
| AI-AC-13 | 내부 API 인증 | `/internal/ai/**` 는 헤더 `X-Ai-Worker-Secret` 이 서버 시크릿과 일치할 때만 통과한다. 틀리거나 없으면 401 `ai_workerUnauthorized`. 서버 시크릿이 비어 있으면 모두 401 이다 |
| AI-AC-14 | 요청자 범위 | 제안·읽기 도구는 요청의 요청자 범위로만 동작한다. 남의 차수에는 제안이 기록되지 않고(`NOT_THE_HOST`), 도구 입력에는 회원 식별자가 없다 |
| AI-AC-15 | 조회·승인은 본인만 | 다른 회원의 요청 조회·승인·거절은 404(존재를 드러내지 않는다) |
| AI-AC-16 | 워커 소비 | claim 이 409 이면 작업 없이 건너뛰고 오프셋을 커밋한다. 실패는 `api.fail` 로 보고한다. 오프셋은 **처리 뒤**에 커밋하고, app-mvc 에 닿지 못하면 커밋하지 않고 다시 처리한다. 임시 폴더는 항상 정리한다 |
| AI-AC-17 | MCP 도구 | 입력은 zod 스키마로 검증하고(빈 목록·음수 가격·51개 등 거절), 서버 오류는 도구 오류로 돌려준다. 노출 도구는 `list_my_rounds`·`get_round_products`·`list_my_projects`·`propose_round_products`·`propose_task` 다섯 개뿐이다 |
| AI-AC-18 | 프런트 화면 로직 | 이미지 사전 검증은 서버와 같은 한도, 폴링은 진행 중 상태에서만, 진행 중이면 제출을 막는다, 제안 0건은 "제안 없음" |
| AI-AC-19 | 직렬 처리 | 워커는 이벤트를 한 번에 하나씩 끝까지 처리한다(파티션 1·소비자 1·병렬 없음). 긴 처리 중에도 heartbeat 를 보내 리밸런스가 나지 않는다 |

## 테스트 매핑

구현하며 채운다 (Task 9).
