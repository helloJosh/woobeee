# API 엔드포인트 · 접근 권한

두 백엔드(app-mvc :8000, app-webflux :8001)의 전체 HTTP/WebSocket 엔드포인트와 필요한
권한을 한 곳에 정리한다. **엔드포인트나 권한이 바뀌면 이 문서를 함께 갱신한다.**

## 권한 모델

- 회원 역할은 `MemberRole` 두 값이다: `ROLE_MEMBER`(회원가입 기본값), `ROLE_ADMIN`(DB 수동 지정).
- 로그인/재발급 시 발급되는 access token 의 메타데이터(`core` 의 `TokenMetadata`)에 역할이
  실리고, 두 앱 모두 이 토큰으로 신원을 판별한다. 요청은 `Authorization: Bearer <accessToken>`.
- **`loginId` 헤더는 서버 내부 전용이다.** app-mvc 의 `AccessTokenLoginIdHeaderFilter` 가
  유효한 토큰에서 파생해 주입하며, 클라이언트가 보낸 값은 항상 무시된다(BLOG-AC-07).
- app-webflux 는 `GameAuthWebFilter` 가 토큰을 검증해 `GamePrincipal` 을 exchange attribute
  로 넣고, 인증이 필요한 핸들러가 `GamePrincipals.require` 로 꺼낸다. 게임 참가는 회원
  토큰 외에 **게스트 토큰**(초대 코드로 발급) 경로가 있다.

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
| GET | `/api/back/pokemon/home` | 첫 화면 — 최근 차수들, 내 주소, 고를 수 있는 통화 | 공개 |
| GET | `/api/back/pokemon/hosts/{handle}` | 한 주최자 — 그가 연 차수들과 **모든 차수의 신청서를 모은 것** (`/pokemon/{handle}`) | 공개 |
| POST | `/api/back/pokemon/hosts` | 내 주소 정하기. 회원당 한 번, 소문자·숫자·하이픈만 | 로그인 |
| PUT | `/api/back/pokemon/hosts/me` | 주최자 설정 — 기본 입금 계좌. 차수를 열 때 자동으로 채워진다 | 주최자 |
| POST | `/api/back/pokemon/rounds` | 차수 개설. 통화·환율 방식(`FIXED`/`PER_ORDER`)을 정한다. 번호는 주최자 안에서 자동. 계좌를 비우면 주최자 기본 계좌를 쓴다 | 로그인 + 주소 있음 |
| GET | `/api/back/pokemon/hosts/{handle}/rounds/{sequence}` | 차수 하나 — 차수·그 차수 상품표·신청서 전체·현재 환율. 주최자 방의 차수 탭이 쓴다 | 공개 |
| PUT | `/api/back/pokemon/rounds/{roundId}` | 차수 수정. 통화는 불변, 환율 변경은 **앞으로의 신청서에만** 적용 | 주최자 |
| PATCH | `/api/back/pokemon/rounds/{roundId}/status` | 차수 상태. `PURCHASED` 최초 진입에 환율이 박혀 환차손익 확정 | 주최자 |
| DELETE | `/api/back/pokemon/rounds/{roundId}` | 차수 삭제 — 신청서가 없을 때만 | 주최자 |
| GET · POST | `/api/back/pokemon/rounds/{roundId}/products` | **그 차수의** 상품표 조회·추가. 통화는 차수를 따라간다 | 주최자 |
| POST | `/api/back/pokemon/rounds/{roundId}/products/from-template` | 내 상품표(틀)에서 그 차수로 복사. 이미 있는 이름은 건너뛴다 | 주최자 |
| POST | `/api/back/pokemon/rounds/{roundId}/orders` | 신청서 제출. **모집중**인 차수에만. 신청자는 회원 닉네임이다 | 로그인 |
| GET | `/api/back/pokemon/orders/{orderId}` | 신청서 세부 — 신청서 + 그 차수 + 현재 환율 | 공개 |
| PUT | `/api/back/pokemon/orders/{orderId}` | 신청서 수정 (전체 교체) | 주최자, 또는 아직 `ORDERED` 인 본인 |
| PATCH | `/api/back/pokemon/orders/{orderId}/status` | 신청서 상태 | 주최자 |
| DELETE | `/api/back/pokemon/orders/{orderId}` | 신청서 삭제 | 주최자, 또는 아직 `ORDERED` 인 본인 |
| POST | `/api/back/pokemon/orders/{orderId}/comments` | 댓글. 작성자는 회원 닉네임이다 | 로그인 |
| DELETE | `/api/back/pokemon/comments/{commentId}` | 댓글 삭제 | 주최자 또는 본인 |
| GET · POST · PUT · DELETE | `/api/back/pokemon/products[/{id}]` | 내 상품표(틀) 관리. 차수를 열 때 그 통화의 것이 복사돼 차수 상품표가 된다 | 주최자 |

상태는 두 층이다. **차수** `OPEN → CLOSED → PURCHASED → DELIVERED`, **신청서**
`ORDERED → DEPOSIT_CONFIRMED → DELIVERED`. 주최자가 전체에 대해 하는 일은 차수가,
개인별로 다른 것(입금)은 신청서가 든다. 둘 다 `CANCELLED` 로 빠질 수 있다.

가격은 요청이 아니라 상품표에서 읽는다 — 클라이언트가 보낸 금액은 쓰지 않는다. 상품 가격을
고쳐도 과거 신청서는 움직이지 않는다(항목이 당시 이름·단가를 스냅샷으로 든다).

집계와 환차손익은 서버가 계산하지 않고 `front/lib/pokemon.ts` 가 한다 — 인수 기준은
`docs/pokemon/PRD.md`.

## app-webflux (:8001)

### game — `/api/game`

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | `/api/game/health` | 헬스 체크 | 공개 |
| GET | `/api/game/me` | 내 게임 프린시펄 확인 | 로그인 |
| POST | `/api/game/rooms` | 방 생성 (초대 코드 발급) | 로그인 |
| GET | `/api/game/rooms/{roomId}?invite=` | 방 요약 (참가 전 확인) | 공개 (유효한 초대 코드 필요) |
| POST | `/api/game/rooms/{roomId}/guest-tokens` | 게스트 토큰 발급 (초대 코드 + 닉네임) | 공개 (유효한 초대 코드 필요) |
| GET | `/api/game/me/results` | 내 전적 목록 | 로그인 |
| GET | `/api/game/results/{gameResultId}/replay` | 기보 presigned URL | 로그인 + **참가자 본인** (GAME-AC-22) |

### WebSocket — `/ws/game`

| 경로 | 설명 | 권한 |
| --- | --- | --- |
| `/ws/game` | 방 입장·게임 진행 실시간 채널. 브라우저가 WebFlux 오리진(:8001)에 직접 붙는다 | JOIN 시 회원 access token 또는 게스트 토큰 검증 (`JoinAuthenticator`) |

## 참고

- 실패 응답은 두 앱 모두 `ApiResponse` 봉투를 지향하나 app-mvc 는 아직 auth 도메인
  advice 만 있어 일부가 봉투 밖으로 나간다(`CLAUDE.md` 후속 과제 참조).
- 게임 도메인의 상세 인수 기준은 `docs/game/PRD.md`, blog/auth 는 각각
  `docs/blog/PRD.md` · `docs/auth/PRD.md` 의 AC 표를 본다. schedule 은 `docs/schedule/PRD.md`
  의 SCHEDULE-AC 표.
