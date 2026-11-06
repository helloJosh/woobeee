# API 엔드포인트 · 접근 권한

백엔드(app-mvc :8000)의 전체 HTTP 엔드포인트와 필요한
권한을 한 곳에 정리한다. **엔드포인트나 권한이 바뀌면 이 문서를 함께 갱신한다.**

## 권한 모델

- 회원 역할은 `MemberRole` 두 값이다: `ROLE_MEMBER`(회원가입 기본값), `ROLE_ADMIN`(DB 수동 지정).
- 로그인/재발급 시 발급되는 access token 의 메타데이터(`core` 의 `TokenMetadata`)에 역할이
  실리고, 이 토큰으로 신원을 판별한다. 요청은 `Authorization: Bearer <accessToken>`.
- **`loginId` 헤더는 서버 내부 전용이다.** app-mvc 의 `AccessTokenLoginIdHeaderFilter` 가
  유효한 토큰에서 파생해 주입하며, 클라이언트가 보낸 값은 항상 무시된다(BLOG-AC-07).

권한 표기:

| 표기 | 의미 |
| --- | --- |
| 공개 | 토큰 불필요 |
| 로그인 | 유효한 access token 필요 (`ROLE_MEMBER` 이상) |
| ADMIN | `ROLE_ADMIN` 토큰 필요 — 무토큰/만료 토큰은 `401`, 유효하나 role 부족은 `403` (둘 다 `ApiResponse` 실패 봉투). 401 이어야 프론트가 refresh 후 재시도한다 |
| 본인 | 로그인 + 리소스 소유자 본인만 |

## app-mvc (:8000)

### auth — `/api/auth`

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | Google OAuth 회원가입 시작 (인가 URL 발급) | 공개 |
| POST | `/api/auth/login` | Google OAuth 로그인 시작 | 공개 |
| POST | `/api/auth/callback-google` | OAuth 콜백 — 토큰 발급. 신규 회원은 `ROLE_MEMBER` 로 생성. **로그인 state 여도 미등록 계정이면 바로 회원가입**(닉네임 = Google 이름 → 이메일 @ 앞 → `Google 사용자`). 비활성 회원은 403 | 공개 (state 검증) |
| POST | `/api/auth/access-tokens` | memberId/role/device 기준 토큰 발급 | 공개 (내부/테스트용) |
| POST | `/api/auth/refresh-tokens` | refresh token 재발급 (rotation, device 일치 검증) | 공개 (refresh token 필요) |
| GET | `/api/auth/me` | 내 프로필 조회 (`hasProfileImage` 보유 여부. 이미지 URL 은 주지 않는다) | 로그인 |
| POST | `/api/auth/me/profile-image` | 프로필 이미지 업로드/교체 (multipart `file`, png·jpeg·webp·gif, 5MB 이하) | 본인 |
| DELETE | `/api/auth/me/profile-image` | 프로필 이미지 삭제 | 본인 |

### blog — `/api/back`

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | `/api/back/posts` | 게시글 목록 (검색 `q`·카테고리 `categoryId`·태그 `tag`(대소문자 무시)·페이징, `Accept-Language` 로 ko/en, `${파일명}` 치환, 각 글에 `description`(영어 없으면 한국어)·`tags`) | 공개 |
| GET | `/api/back/posts/{postId}` | 게시글 상세 (조회수 증가, `${파일명}` → 이미지 엔드포인트 상대 경로 치환, `description`·`tags`) | 공개 |
| POST | `/api/back/posts` | 게시글 작성 (multipart: `request` JSON(`titleKo`,`titleEn`,`categoryId`,`descriptionKo`/`descriptionEn`(선택·300자),`tags[]` ≤10개·30자) + `markdownKr`/`markdownEn` + `file`*) | **ADMIN** |
| PUT | `/api/back/posts/{postId}` | 게시글 수정 (같은 multipart 계약, 마크다운 파트 없으면 본문 보존, `tags` 는 집합 교체) | **ADMIN** + 작성자 본인 |
| DELETE | `/api/back/posts/{postId}` | 게시글 삭제 (태그 연결 캐스케이드) | **ADMIN** + 작성자 본인 |
| GET | `/api/back/tags` | 인기 태그 (`limit` 기본 20 — 글 수 내림차순, 글 있는 태그만) | 공개 |
| GET | `/api/back/categories` | 카테고리 트리 조회 | 공개 |
| POST | `/api/back/categories/{parentId}` | 카테고리 생성 | **ADMIN** |
| DELETE | `/api/back/categories/{categoryId}` | 카테고리 삭제 | **ADMIN** |
| GET | `/api/back/comments/{postId}` | 게시글 댓글 목록 | 공개 (로그인 시 본인 판별 포함) |
| POST | `/api/back/comments` | 댓글/대댓글 작성 | 로그인 |
| DELETE | `/api/back/comments/{commentId}` | 댓글 삭제 | 본인 |
| POST | `/api/back/likes/{postId}` | 좋아요 등록 | 로그인 |
| DELETE | `/api/back/likes/{postId}` | 좋아요 취소 | 로그인 |

ADMIN 게이트는 `AccessTokenLoginIdHeaderFilter` 가 경로·메서드 매트릭스
(`POST/PUT/DELETE` × `/api/back/posts**`·`/api/back/categories**`)로 강제한다.
근거 테스트: `AccessTokenLoginIdHeaderFilterTest` (BLOG-AC-07~10, 12), `PostServiceImplTest` (BLOG-AC-11).
access token TTL 은 role 로 갈린다 — `ROLE_ADMIN` 1일, 그 외 15분 (AUTH-AC-17, `TokenServiceTest`).

### schedule — `/api/back/schedule`

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | `/api/back/schedule/tree` | 프로젝트/마일스톤/할 일 트리 조회 (배치 쿼리 5회 — 알림·이슈 포함, 각 할 일에 `issues`). 할 일·마일스톤은 시작일 오름차순(미정은 뒤). 최상위 `tasks` 는 무소속 할 일. 조회 직전에 종료일이 지난(어제 이전) 항목을 세 층 모두 자동으로 완료 처리한다 — 종료일 미정(NULL)·당일, 마감 후 직접 수정한 항목(updated_at > 종료일), 그리고 보류(`ON_HOLD`)·오류(`ERROR`) 상태는 제외 | 로그인 |
| POST | `/api/back/schedule/projects` | 프로젝트 생성 | 로그인 |
| PUT | `/api/back/schedule/projects/{projectId}` | 프로젝트 수정 | 로그인 + 본인 |
| DELETE | `/api/back/schedule/projects/{projectId}` | 프로젝트 삭제 (하위 마일스톤·할 일 캐스케이드) | 로그인 + 본인 |
| POST | `/api/back/schedule/milestones` | 마일스톤 생성 | 로그인 |
| PUT | `/api/back/schedule/milestones/{milestoneId}` | 마일스톤 수정 | 로그인 + 본인 |
| DELETE | `/api/back/schedule/milestones/{milestoneId}` | 마일스톤 삭제 (하위 할 일 캐스케이드) | 로그인 + 본인 |
| POST | `/api/back/schedule/tasks` | 할 일 생성 — `projectId` 생략 시 무소속. `startTime`/`endTime`(`HH:mm`, 선택), `reminders`(10·30 분 전 — 시작일+시작 시간 필요), `color`(`#RRGGBB`, 선택 — 생략 시 자동 배정) | 로그인 |
| PUT | `/api/back/schedule/tasks/{taskId}` | 할 일 수정 — 전체 교체(`reminders` 집합 포함) | 로그인 + 본인 |
| DELETE | `/api/back/schedule/tasks/{taskId}` | 할 일 삭제 (이슈·알림 캐스케이드) | 로그인 + 본인 |
| POST | `/api/back/schedule/tasks/{taskId}/issues` | 할 일 밑 이슈 생성 — `content`(≤1000자), 미해결로 시작 | 로그인 + 본인(할 일) |
| PUT | `/api/back/schedule/issues/{issueId}` | 이슈 수정 — `content`, `resolved` 전체 교체 | 로그인 + 본인(부모 할 일) |
| DELETE | `/api/back/schedule/issues/{issueId}` | 이슈 삭제 | 로그인 + 본인(부모 할 일) |
| GET | `/api/back/schedule/notification` | Slack 알림 설정 조회 (`webhookUrl`, null = 미사용) | 로그인 |
| PUT | `/api/back/schedule/notification` | Slack Incoming Webhook URL 등록 — `https://hooks.slack.com/` 접두만 허용 | 로그인 |
| DELETE | `/api/back/schedule/notification` | Slack 알림 해제 | 로그인 |

매일 09:00(Asia/Seoul)에 `ScheduleSlackNotifier`(`@Scheduled`)가 webhook 을 등록한 멤버에게
오늘 마감·오늘 시작·기한 경과(자동 완료 처리되는) 할 일 요약을 발송한다. 세 목록이 모두 비면
보내지 않고, 한 멤버의 발송 실패는 다음 멤버 발송을 막지 않는다.

ADMIN 전용 엔드포인트는 없다. 소유권 검증은 컨트롤러가 아니라 서비스 계층에서 이뤄지며,
본인 소유가 아니거나 존재하지 않는 리소스는 구분 없이 404 로 응답한다. FK 제약은 두지 않고
같은 계층에서 프로젝트 간 이동 금지·깊이 제한·순환 참조·날짜 범위·색상 형식을 검증한다.

### pokemon — `/api/back/pokemon`

주최자가 **차수**(1차·2차·3차)를 열고 회원들이 거기에 신청한다. 통화·환율·계좌·마감일은 차수가
들고 있고, **상품표도 차수마다** 따로다(내 상품표를 틀로 복사해 심는다).

조회는 공개지만 **쓰는 것은 로그인이 필요하다** — 신청·댓글·차수 개설 모두.

| 메서드 | 경로 | 설명 | 접근 |
| --- | --- | --- | --- |
| GET | `/api/back/pokemon/home` | 첫 화면 — 내 주소·기본 계좌, 고를 수 있는 통화. 차수 목록은 싣지 않는다(주최자 주소로 찾아 들어간다) | 공개 |
| GET | `/api/back/pokemon/hosts/{handle}` | 한 주최자와 그가 연 차수들 (`/pokemon/{handle}`). 신청서는 싣지 않는다 — 화면이 한 번에 한 차수만 보므로 차수 조회가 준다 | 공개 |
| POST | `/api/back/pokemon/hosts` | 내 주소 정하기. 회원당 한 번, 소문자·숫자·하이픈만 | 로그인 |
| PUT | `/api/back/pokemon/hosts/me` | 주최자 설정 — 기본 입금 계좌. 차수를 열 때 자동으로 채워진다 | 주최자 |
| POST | `/api/back/pokemon/rounds` | 차수 개설. 통화를 정한다(환율 방식은 없다). 번호는 주최자 안에서 자동. 계좌를 비우면 주최자 기본 계좌를 쓴다 | 로그인 + 주소 있음 |
| GET | `/api/back/pokemon/hosts/{handle}/rounds/{sequence}` | 차수 하나 — 차수·그 차수 상품표·신청서 전체(`estimatedItemsKrw`·`estimatedKrw`)·오늘 환율(`currentRate`: `toKrw`·`fetchedAt`·`stale`). 주최자 방의 차수 탭이 쓴다 | 공개 |
| PUT | `/api/back/pokemon/rounds/{roundId}` | 차수 수정(제목·계좌·마감일·메모). 통화는 불변 | 주최자 |
| PATCH | `/api/back/pokemon/rounds/{roundId}/status` | 차수 상태. 환율을 읽지 않는다(환율 확정·환차손익 없음) | 주최자 |
| DELETE | `/api/back/pokemon/rounds/{roundId}` | 차수 삭제 — 신청서가 없을 때만 | 주최자 |
| GET · POST | `/api/back/pokemon/rounds/{roundId}/products` | **그 차수의** 상품표 조회·추가. 통화는 차수를 따라간다 | 주최자 |
| POST | `/api/back/pokemon/rounds/{roundId}/products/from-template` | 내 상품표(틀)에서 그 차수로 복사. 이미 있는 이름은 건너뛴다 | 주최자 |
| GET | `/api/back/pokemon/orders/me` | 내 신청서 목록 — 차수·주최자 핸들을 조인해 최신순. `orderId`·`roundId`·`hostHandle`·`roundSequence`·`roundTitle`·`currency`·`roundStatus`·`orderStatus`·`estimatedKrw`·`createdAt`. 비로그인은 401 `pokemon_loginRequired`. 마이페이지가 쓴다 | 로그인 |
| POST | `/api/back/pokemon/rounds/{roundId}/orders` | 신청서 제출. **모집중**인 차수에만. 신청자는 회원 닉네임이다 | 로그인 |
| GET | `/api/back/pokemon/orders/{orderId}` | 신청서 세부 — 신청서(예상 금액) + 그 차수 + 오늘 환율 | 공개 |
| PUT | `/api/back/pokemon/orders/{orderId}` | 신청서 수정 (전체 교체) | 주최자, 또는 아직 `ORDERED` 인 본인 |
| PATCH | `/api/back/pokemon/orders/{orderId}/status` | 신청서 상태. 응답의 예상 금액은 환율을 모르면 `null` 이며 이 때문에 상태 변경이 실패하지는 않는다 | 주최자 |
| DELETE | `/api/back/pokemon/orders/{orderId}` | 신청서 삭제 | 주최자, 또는 아직 `ORDERED` 인 본인 |
| POST | `/api/back/pokemon/orders/{orderId}/comments` | 댓글. 작성자는 회원 닉네임이다 | 로그인 |
| PUT | `/api/back/pokemon/comments/{commentId}` | 댓글 수정 — 내용만. 고치면 `updatedAt` 이 박혀 화면이 "(수정됨)" 을 붙인다 | **본인만** |
| DELETE | `/api/back/pokemon/comments/{commentId}` | 댓글 삭제 | 주최자 또는 본인 |
| GET · POST · PUT · DELETE | `/api/back/pokemon/products[/{id}]` | 내 상품표(틀) 관리. 차수를 열 때 그 통화의 것이 복사돼 차수 상품표가 된다 | 주최자 |

표의 "주최자" 는 **그 차수를 연 사람 또는 전역 운영자**(`pokemon.manager-member-ids`)다.
운영자는 **한 명**이고, 자기 차수는 주최자 자격으로 이미 주무를 수 있으므로 명단을 늘리지
않는다. 댓글 수정만 예외로 운영자에게도 열지 않는다 — 지우는 것은 중재지만 고치는 것은 남의
이름으로 다른 말을 남기는 일이다. 이 경계는 `PokemonServiceImplTest` 가 고정한다.

상태는 두 층이다. **차수** `OPEN → CLOSED → PURCHASED → DELIVERED`, **신청서**
`ORDERED → DEPOSIT_CONFIRMED → DELIVERED`. 주최자가 전체에 대해 하는 일은 차수가,
개인별로 다른 것(입금)은 신청서가 든다. 둘 다 `CANCELLED` 로 빠질 수 있다.

가격은 요청이 아니라 상품표에서 읽는다 — 클라이언트가 보낸 금액은 쓰지 않는다. 상품 가격을
고쳐도 과거 신청서는 움직이지 않는다(항목이 당시 이름·단가를 스냅샷으로 든다).

집계는 서버가 계산하지 않고 `front/lib/pokemon.ts` 가 한다(환차손익은 없다 — 원화는 오늘 환율의 예상 금액이다) — 인수 기준은
`docs/pokemon/PRD.md`.

### ai — `/api/back/ai` · `/internal/ai`

글·이미지를 올리면 Claude Code 가 공동구매 상품·일정을 **제안**하고, 사용자가 승인하면 등록한다
(`docs/ai/PRD.md`, ADR-005). 실패 응답은 `header.message` 에 코드 키(`ai_*`)를 싣는다.

**사용자 API** — 전부 로그인 필수, 남의 요청은 `404`(존재를 드러내지 않는다).

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| POST | `/api/back/ai/requests` | 요청 접수 — multipart `text`, `images`(≤5장, 장당 ≤5MB, png/jpeg/webp). 요청과 outbox 를 한 트랜잭션으로 저장한다. 진행 중인 요청이 있으면 `409 ai_requestInProgress` | 로그인 |
| GET | `/api/back/ai/requests/latest` | 내 가장 최근 요청(없으면 `data` null). 새로고침한 화면이 진행 중인 요청을 다시 찾는다 | 로그인 |
| GET | `/api/back/ai/requests/{id}` | 상태·요약·제안 목록(`actions`) | 본인 |
| POST | `/api/back/ai/requests/{id}/retry` | `DEAD` 요청을 다시 돌린다(`409 ai_notRetryable` 그 외) | 본인 또는 전역 운영자 |
| POST | `/api/back/ai/requests/{id}/actions/{actionId}/apply` | 제안 승인 — 요청자 권한으로 기존 서비스를 호출해 등록한다. 두 번 눌러도 한 번만 반영(`409 ai_actionAlreadyDone`). 거절되면 `403 ai_applyForbidden`/`400 ai_applyRejected` 이고 제안은 `PENDING` 으로 남는다 | 본인 |
| POST | `/api/back/ai/requests/{id}/actions/{actionId}/reject` | 제안 거절 | 본인 |

**내부 API** — 워커 전용. 헤더 `X-Ai-Worker-Secret` 이 서버 시크릿과 같을 때만 열린다(`401 ai_workerUnauthorized`,
서버 시크릿이 비어 있으면 전부 401). 프런트 rewrites 에 없는 경로라 브라우저에서는 닿지 않는다. 쓰기·읽기 모두
claim 이 준 `attempt` 가 현재와 같을 때만 받는다(`409 ai_staleAttempt`).

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/internal/ai/requests/{id}/claim` | 처리 시작. 이미 처리됐거나 리스가 살아 있으면 `409 ai_notClaimable`. 응답에 `attempt`, `text`, `imageKeys` |
| POST | `/internal/ai/requests/{id}/complete` | 처리 완료(`{attempt, summary}`) → `DRAFTED` |
| POST | `/internal/ai/requests/{id}/fail` | 실패 보고(`{attempt, error}`) → 3회 미만 재발행, 3회째 `DEAD` + DLQ |
| POST | `/internal/ai/requests/{id}/proposals/round-products` | 상품 제안 기록(`{attempt, roundId, items[]}`) — 등록하지 않는다 |
| POST | `/internal/ai/requests/{id}/proposals/tasks` | 할 일 제안 기록(`{attempt, task}`) — 등록하지 않는다 |
| GET | `/internal/ai/requests/{id}/pokemon/rounds?attempt=` | 요청자가 연 차수 |
| GET | `/internal/ai/requests/{id}/pokemon/rounds/{roundId}/products?attempt=` | 요청자 차수의 상품표(남의 차수는 `403`) |
| GET | `/internal/ai/requests/{id}/schedule/tree?attempt=` | 요청자의 일정 트리 |

## 참고

- 실패 응답은 `ApiResponse` 봉투를 지향하나 app-mvc 는 아직 auth 도메인
  advice 만 있어 일부가 봉투 밖으로 나간다(`CLAUDE.md` 후속 과제 참조).
- 상세 인수 기준은 blog/auth 는 각각
  `docs/blog/PRD.md` · `docs/auth/PRD.md` 의 AC 표를 본다. schedule 은 `docs/schedule/PRD.md`
  의 SCHEDULE-AC 표.
