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

| 메서드 | 경로 | 설명 | 접근 |
| --- | --- | --- | --- |
| GET | `/api/back/pokemon/board` | 진행도 화면이 쓰는 단일 조회 — 현재 환율, 상품표(App Store 에서 매일 동기화, 활성 상품만), 신청서 전체(항목·댓글 포함, 최신순), 입금 계좌, 요청자의 운영자 여부(`canManage`). 항목과 댓글은 각각 `orderId IN (...)` 한 번으로 모아 온다 | 공개 |
| GET | `/api/back/pokemon/orders/{orderId}` | 신청서 세부 조회 — 신청서 한 건(항목·댓글) + 현재 환율 + 계좌 + `canManage`. 세부 페이지가 쓴다 | 공개 |
| GET | `/api/back/pokemon/rate` | 현재 INR→KRW. Redis 캐시(TTL 1시간)를 거치고, 외부 조회 실패 시 마지막 성공값을 `stale: true` 로 내보낸다 | 공개 |
| POST | `/api/back/pokemon/orders` | 신청서 제출. 로그인이면 회원 닉네임이 신청자명(요청 값 무시), 비회원은 `applicantName` 필수. 환율·환산액·이체액을 이 시점 값으로 행에 박는다. `extraInr` 로 상품표에 없는 금액을 직접 넣을 수 있고, 그때는 `items` 가 비어도 된다 | 공개 |
| PUT | `/api/back/pokemon/orders/{orderId}` | 신청서 수정 — 항목·기부금·자유 루피·메모 전체 교체. 금액은 **수정 시점 환율**로 다시 계산되고 환율 스냅샷도 갱신된다(`settled_rate` 는 그대로) | 삭제와 같은 규칙 |
| PATCH | `/api/back/pokemon/orders/{orderId}/status` | 진행 상태 변경. `PREPARING`(= 실제로 결제하는 단계)으로 처음 넘어갈 때 그 순간의 환율이 `settled_rate` 에 박혀 환차손익이 확정된다 | 운영자 |
| DELETE | `/api/back/pokemon/orders/{orderId}` | 신청서 삭제 (항목·댓글 함께) | 운영자. 그 밖에는 아직 `ORDERED` 일 때만 — 회원이 낸 것은 그 회원, **비회원이 낸 것은 누구나**(주인이 없어 본인 확인이 성립하지 않는다) |
| POST | `/api/back/pokemon/orders/{orderId}/comments` | 신청서에 댓글. 로그인이면 닉네임이 작성자, 비회원은 `authorName` 필수 | 공개 |
| DELETE | `/api/back/pokemon/comments/{commentId}` | 댓글 삭제 | 운영자 또는 본인 |

진행 상태는 `ORDERED`(주문) → `PREPARING`(준비중) → `DEPOSIT_CONFIRMED`(입금확인)
→ `DELIVERED`(배달 완료)이고, `CANCELLED` 는 어느 단계에서든 빠져나간다.
**준비중이 입금확인보다 앞이다** — 돈을 받기 전에 먼저 사 두는 운영 방식이기 때문이다.

**운영자는 blog 의 `ROLE_ADMIN` 이 아니다.** `pokemon.manager-member-ids`(기본 `1,3`) 명단으로
판정하며, 필터가 아니라 서비스 계층에서 본다 — 같은 경로 prefix 안에 공개 쓰기(신청·댓글)와
운영자 전용 쓰기가 섞여 있어 `AccessTokenLoginIdHeaderFilter` 의 경로 prefix 게이팅으로는
나눌 수 없기 때문이다.

상품표는 매일 06:00(Asia/Seoul) `PokemonProductSyncService` 가 App Store 제품 페이지를 읽어
갱신한다. 신청 항목은 상품 **id** 로 보내고, 서버가 상품표에서 가격을 읽어 금액을 만든다 —
클라이언트가 보낸 금액은 쓰지 않는다.

집계(총액·진행률·환차손익·미입금 명단)는 서버가 계산하지 않는다. 원천 데이터와 현재 환율만
내려주고 `front/lib/pokemon.ts` 가 계산한다 — 인수 기준은 `docs/pokemon/PRD.md`.

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
