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
| POST | `/api/auth/callback-google` | OAuth 콜백 — 토큰 발급. 신규 회원은 `ROLE_MEMBER` 로 생성 | 공개 (state 검증) |
| POST | `/api/auth/access-tokens` | memberId/role/device 기준 토큰 발급 | 공개 (내부/테스트용) |
| POST | `/api/auth/refresh-tokens` | refresh token 재발급 (rotation, device 일치 검증) | 공개 (refresh token 필요) |
| GET | `/api/auth/me` | 내 프로필 조회 (presigned 이미지 URL 포함) | 로그인 |
| POST | `/api/auth/me/profile-image/presigned-url` | 프로필 이미지 업로드 presigned URL 발급 | 로그인 (본인 prefix 만) |
| PUT | `/api/auth/me/profile-image` | 프로필 이미지 등록/교체 | 본인 |
| DELETE | `/api/auth/me/profile-image` | 프로필 이미지 삭제 | 본인 |

### blog — `/api/back`

| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | `/api/back/posts` | 게시글 목록 (검색·카테고리·페이징, `Accept-Language` 로 ko/en) | 공개 |
| GET | `/api/back/posts/{postId}` | 게시글 상세 (조회수 증가, `${파일명}` → 공개 URL 치환) | 공개 |
| POST | `/api/back/posts` | 게시글 작성 (multipart: `request` JSON + `markdownKr`/`markdownEn` + `file`*) | **ADMIN** |
| PUT | `/api/back/posts/{postId}` | 게시글 수정 (같은 multipart 계약, 마크다운 파트 없으면 본문 보존) | **ADMIN** + 작성자 본인 |
| DELETE | `/api/back/posts/{postId}` | 게시글 삭제 | **ADMIN** + 작성자 본인 |
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
  `docs/blog/PRD.md` · `docs/auth/PRD.md` 의 AC 표를 본다.
