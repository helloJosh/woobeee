# FRONTEND

프론트엔드는 `front/` 하위 Next.js 14 애플리케이션이다. 화면이 제공해야 할 제품 목표와 사용자
여정은 [`front/PRD.md`](front/PRD.md)에 있고, 이 문서는 **구현 기준**을 다룬다.

## 기술 스택

- Next.js 14 App Router
- React 18
- TypeScript (`strict: true`)
- Tailwind CSS
- Radix UI 기반 컴포넌트
- lucide-react 아이콘
- vitest (`lib/*.test.ts`)

## 디렉터리 구조

```text
front/
|-- app/                 라우트와 전역 레이아웃
|-- components/          화면 컴포넌트
|-- components/game/     게임 화면 컴포넌트 (판·격자·사이드바·참가 게이트·기보 뷰어)
|-- components/ui/       공통 UI primitive
|-- hooks/               React 컨텍스트와 훅
|-- lib/                 API, 타입, 에러 처리, 그리고 **React 밖으로 뺀 판단 모듈**
|-- lib/*.test.ts        위 판단 모듈의 vitest 스펙 (컴포넌트 옆이 아니라 대상 옆에 둔다)
|-- public/              정적 리소스
|-- vitest.config.ts     테스트 러너 설정
`-- package.json         프론트 빌드/실행/테스트 스크립트
```

레포 루트의 `scripts/dodge-parity-trace.jsh` 도 프론트를 위한 것이다 — 아래
[기보 재생](#기보-재생)을 참고한다.

## 라우팅

상단탭은 세 개다: 홈 / 기술블로그 / 마이페이지. 홈이 곧 게임 허브다 — 별도 랜딩 없이
`/` 가 오목·장애물피하기 카드를 그린다(`components/game/game-hub.tsx`, 카드 상단의
픽셀아트는 `components/game/game-art.tsx` 가 SVG 로 직접 그린다 — 이미지 에셋이 없다).
`Header` 는 루트 레이아웃(`app/layout.tsx`)에서 마운트되므로 모든 라우트에 나온다.

| 경로 | 화면 |
| --- | --- |
| `/` | 게임 허브. 오목·장애물피하기 카드 |
| `/blog`, `/blog/[postId]` | 기술블로그 목록·상세 |
| `/mypage` | 프로필 + 전적 + 기보 다시보기 (회원 전용) |
| `/game` | `/` 로 리다이렉트 (기존 북마크·화면 내 `/game` 링크 보존용) |
| `/game/omok/[roomId]?invite=` | 오목 플레이 |
| `/game/dodge/[roomId]?invite=` | 장애물피하기 플레이 |
| `/login`, `/signup`, `/logout` | 인증 |
| `/auth/google/callback` | Google authorization callback 처리 |

`app/products`, `app/cart`, `app/chat` 은 폐기된 product/cart 백엔드를 호출한다. 살아 있는
화면에서 이 세 경로로 들어가는 진입 링크는 걷어냈지만, 폐기 화면끼리는 아직 서로 링크한다
(`app/products/[productId]/page.tsx` 의 상단 바가 여전히 `/cart` 로 이어진다). 페이지 삭제는
후속 과제다 — **이 세 경로의 규칙은 더 이상 유효하지 않다.**

방 경로의 모양(`/game/<segment>/<roomId>?invite=<code>`)을 아는 곳은 `lib/game-join.ts` 의
`roomPath` 하나다. 방을 만든 사람이 가는 URL 과 로그인을 마친 초대 손님이 돌아오는 URL 이
같아야 하므로, 다른 곳에서 직접 조립하지 않는다.

## 판단은 React 밖 모듈에 둔다

이 프론트엔드의 핵심 규약이다. **화면 상태를 정하는 판단은 컴포넌트가 아니라 `lib/` 의
React-free 모듈에 두고, 컴포넌트는 그 결과를 그리기만 한다.**

| 모듈 | 담는 판단 |
| --- | --- |
| `lib/game-hub.ts` | 방 만들기 — 인증 확인, 생성 API, 이동할 URL, 실패 문구 |
| `lib/game-join.ts` | 초대 링크 참가 — 방 요약 조회, 회원/게스트 갈림길, 닉네임 검사, 게스트 토큰 저장·폐기, 방 경로 |
| `lib/game-room.ts` | 두 게임 화면 공통 — 명단에서 나를 찾기, 신뢰할 memberId 고르기, 소켓 상태 문구 |
| `lib/game-socket.ts` | `/ws/game` 소켓의 유일한 소유자 — 재접속 백오프, 거절 판정, `seq` 관리, 봉투 직렬화·파싱 |
| `lib/room-sidebar.ts` | 방장 판정, 시작 가능 판정, 초대 링크 클립보드 복사 |
| `lib/omok-play.ts` | 오목 소켓 메시지 → 화면 상태 리듀서, 착수 가능 판정, 차례·결과 문구 |
| `lib/dodge-play.ts` | 장애물피하기 리듀서, 색·번호 배정, 입력 스로틀·방향 매핑, 진행·결과 문구 |
| `lib/dodge-engine.ts` | 서버 `DodgeGame` 의 TypeScript 포트 (기보 재생 전용) |
| `lib/replay-view.ts` | 기보 파싱(오목 v1 / 장애물 v2), 재생 프레임 구성, 전적 목록 페이징 |
| `lib/auth-redirect.ts` | 로그인 후 복귀 경로 — `next` 살균, 링크 조립, OAuth 왕복 보관 |
| `lib/game-errors.ts` | 게임 API 오류 문구, 유니온 소진 검사(`assertNever`) — `lib/game-hub.ts` 가 기존 호출부(`app/game/page.tsx`)를 위해 그대로 재수출한다 |
| `lib/blog-admin.ts` | 블로그 글 관리 — ADMIN 판정(`canManagePosts`), 초안 검증, multipart 조립, 카테고리 트리 평탄화, 드롭 이미지 보류·`${파일명}` 치환(`resolvePendingImages`), 마크다운 편집기 커서 삽입·드롭 이미지 수집(`insertSnippet`/`collectDroppedImages`) |

(`game-socket.ts` 만 `"use client"` 를 달고 있다. Next 의 번들링 지시어일 뿐 React 에 의존하지는
않으며, 다른 모듈과 똑같이 테스트된다.)

**이유는 취향이 아니라 실측이다.** 리뷰에서 세 차례 뮤테이션 스윕을 돌려 도합 84개의 변이를
심었다(11 + 31 + 42). **`lib/` 안의 변이는 결국 전부 죽었다** — 1차는 11/11, 2차는 대상 모듈
안의 25개 전부, 3차는 42개 중 36개를 즉시 죽이고 살아남은 6개는 고친 뒤 재검증에서 다시 전부
죽였다.

**끝까지 죽지 않은 변이는 전부 React 컴포넌트 층에 있었다.** 그럴 수밖에 없다 — 컴포넌트
스펙이 하나도 없기 때문이다(스펙은 전부 `lib/*.test.ts` 다). 즉 판단이 컴포넌트로 넘어가는
만큼 그대로 검증 밖으로 나간다. 리듀서의 조건 하나가 조용히 뒤집혀도 화면은 계속 그려지므로,
컴포넌트에 남은 판단은 틀린 채로 몇 주를 간다. 실제로 2차 스윕에서 "판단을 모듈로 옮겼더니
스윕이 처음으로 깨끗하게 돌아왔다" 가 이 규약이 세워진 계기였다. 다음 사람이 "이 정도는
컴포넌트에 둬도 되지 않나" 로 되돌리고 싶어질 텐데, 되돌리는 순간 그만큼을 잃는다.

파생 규칙 몇 가지:

- 새 판단은 `lib/` 에 두고 `lib/<name>.test.ts` 를 함께 만든다. 컴포넌트는 props 와 렌더만 갖는다.
  이 규칙이 세워지기 전에 쓰인 `game-hub.ts`·`room-sidebar.ts`·`game-errors.ts`·`game-config.ts`
  는 아직 테스트가 없다 — 실측(다음 문단)이 다루는 뮤테이션 스윕도 이 넷은 대상이 아니었다.
  새로 손댈 때 반드시 테스트를 채운다.
- 게임에 국한되지 않는 것(명단에서 나 찾기, 소켓 상태 문구)은 `game-room.ts` 로 간다. 두 번째
  화면이 첫 번째 화면의 모듈을 import 하기 시작하면 그 모듈이 공용 모듈 행세를 하게 된다.
- 예외 하나: **Tailwind 클래스 문자열은 `components/` 에 둔다.** `tailwind.config.ts` 의
  content 글롭이 `app/`·`components/` 만 훑고 `lib/` 는 훑지 않아서, `lib/` 로 옮기면 클래스가
  생성되지 않는다(`DODGE_PLAYER_COLORS` 가 `components/game/dodge-grid.tsx` 에 있는 이유).
  `lib` 쪽은 색 *인덱스*만 계산한다.
- `switch` 로 유니온을 다룰 때는 `default: assertNever(x)` 를 둔다. 없으면 새 분기가 추가돼도
  컴파일러가 조용히 넘어간다.

## WebSocket

**`next.config.mjs` 의 rewrites 는 HTTP 만 프록시한다.** WebSocket 은 rewrites 를 타지 못하므로
브라우저가 `NEXT_PUBLIC_WS_BASE_URL` 로 WebFlux 오리진에 직접 붙는다(`lib/game-config.ts`).
값이 없으면 개발 기본값 `ws://localhost:8001` 로 폴백하고 콘솔에 경고를 남긴다 — 같은 오리진으로
폴백하면 소켓이 Next 로 가고 Next 는 업그레이드를 못 하므로 반드시 실패하기 때문이다.

`/ws/game` 소켓을 소유하는 곳은 `lib/game-socket.ts` 하나다. 화면은 이 모듈이 넘겨 주는 이벤트만
구독하고 raw `WebSocket` 을 직접 열지 않는다 — 재접속과 `seq` 관리가 화면마다 갈라지면 두 게임의
동작이 조금씩 달라진다.

- 재접속은 최대 5회, `500ms` 부터 지수 백오프. 서버가 참가를 거절해 닫은 세션(`rejected`)은
  재시도하지 않는다.
- **봉투는 방향마다 다르다.** 나가는 것은 `{type, seq, payload}`(`ClientMessage`), 들어오는 것은
  `{type, ackSeq?, payload}`(`ServerMessage`)다. `ackSeq` 는 **선택 필드**이고 서버가
  `ServerMessage.ack(...)` 로 보낸 프레임(`OMOK_MOVED`·`OMOK_REJECTED`·`ERROR`)에만 붙는다 —
  나머지에는 필드 자체가 없다. 두 타입을 하나로 뭉뚱그리면 `ROOM_STATE` 에서 `ackSeq` 를 읽는
  코드가 그대로 통과한다.
- 들어오는 `payload` 의 타입은 `unknown` 이다. `isServerMessage(message, "ROOM_STATE")` 로 좁혀서
  읽는 것이 유일한 정문이다 — `any` 로 두면 옮겨 적은 페이로드 타입들이 전부 장식이 되고,
  승리 착수에 존재하지도 않는 `payload.nextTurn` 을 읽는 코드가 컴파일된다.
- 타입은 `app-webflux` 의 `ClientMessage`/`ServerMessage` 에서 필드 단위로 옮겨 왔다 — 서버
  계약이 바뀌면 여기가 단일 갱신 지점이다.
- 진행 중인 게임에 재접속하면 서버가 `GAME_SNAPSHOT` 을 보낸다. 오목은 지금까지의 착수 목록과
  차례·마감시각, 장애물피하기는 현재 `tick`·`positions`·`obstacles` 다. 리듀서는 이것을 받으면
  누적 상태를 통째로 갈아 끼운다(`docs/game/PRD.md` GAME-AC-23~25).
- 소켓이 `rejected` 로 끝나면 그 방의 게스트 토큰을 버린다(`discardGuestTokenOnRejection`).
  안 버리면 죽은 토큰을 새로고침마다 다시 물려 게스트가 닉네임 폼으로 돌아갈 길이 없어진다.

## 게임 참가와 신원

- 회원의 `participantId` 는 `m:<memberId>`, 게스트는 발급 응답이 준 `g:<uuid>` 다. 규칙을 아는
  곳은 `lib/game-room.ts` 의 `memberParticipantId` 하나다.
- 게스트 토큰과 `participantId` 는 `sessionStorage` 에 방 단위로 6시간 TTL 로 둔다(서버
  `GuestIdentityService.GUEST_TOKEN_TTL` 과 같은 값). 키는 `woobeee:game:guest-token:<roomId>`
  (`game-join.ts` 의 `guestTokenKey`). 탭 단위라 같은 브라우저의 다른 탭이 같은
  자리를 집어가지 않는다.
- 게임 화면의 `memberId` 는 `GET /api/game/me` 가 준 값을 우선하고, 실패하거나 게스트면
  `localStorage` 에 적어 둔 값으로 되돌아간다(`useVerifiedMemberId` + `chooseMemberId`).
  이 호출만 전역 401 처리를 끈다 — 배경 호출 하나가 멀쩡히 돌아가는 판을 끝내면 안 된다.
- 닉네임 규칙(trim 후 1~20자, ISO 제어문자 금지)은 서버 `NicknameValidator` 와 같다. 클라이언트
  검사는 왕복 한 번을 아끼는 것일 뿐 최종 권한은 서버에 있다.

## 게임 오류 코드

`app-webflux` 는 실패를 `ApiResponse` 봉투로 내려보내고 `header.message` 에 `game_*` 코드를
싣는다. `front/lib/errors/error-messages.ts` 가 그 코드를 사용자 문구로 옮긴다.

**이 지도와 서버의 `GameErrorCode` 카탈로그는 양방향으로 일치해야 한다** — 지도에 없는 코드도,
코드 없는 지도 키도 서버 테스트(`GameErrorCodeTest`)가 실패시킨다. 코드를 추가할 때는 ko/en
양쪽을 함께 넣는다.

게임 화면의 `gameAPI` 호출은 모두 `suppressAlert: true` 로 blocking `alert()` 을 끄고 인라인
배너로 안내한다. 네트워크 레벨 실패(백엔드 다운·오프라인·CORS)는 `apiRequest` 에 닿기도 전에
`fetch` 가 `TypeError` 를 던지므로 친절한 메시지가 없다 — 그 경로만 `describeGameApiError` 가
문구를 채운다.

## 기보 재생

`lib/dodge-engine.ts` 는 서버 `DodgeGame` 의 TypeScript 포트다. 장애물피하기 기보는 시드와
입력·이탈만 담고 있어서, 재생은 이 포트로 같은 틱 로직을 다시 돌려 만든다. **서버와 한 글자라도
달라지면 재생 결과가 갈라지므로 양쪽을 같이 고친다.**

포트는 골든으로 고정돼 있고, 그 골든은 서버 바이트코드에서 직접 뽑는다:

```bash
./mvnw -pl core,app-webflux -am compile -DskipTests
jshell --class-path app-webflux/target/classes -q scripts/dodge-parity-trace.jsh
```

출력은 `lib/dodge-engine.test.ts` 의 `GOLDEN` 배열과 한 글자도 다르지 않은 형식이다. **자바
쪽 `DodgeGame` 을 고쳤다면 이 스크립트를 다시 돌려 `GOLDEN` 을 갱신해야 한다** — 갱신하지 않으면
프론트 테스트는 낡은 기대값에 대고 계속 초록이다. 그것이 이 골든의 유일한 약점이라 재현을 한
줄로 만들어 두었다.

`lib/dodge-engine.ts` 를 **실시간 플레이 화면에서 돌리지 않는다.** 판의 권위는 전적으로 서버에
있고, 같은 엔진을 화면에서 함께 돌리면 서버 프레임과 예측 프레임 두 개의 진실이 생겨 반드시
갈라진다. 화면은 서버가 보내 준 프레임만 그린다.

기보는 두 종류이고 파서를 공유하지 않는다(`lib/replay-view.ts`).

| 게임 | 형식 | 재생 방식 |
| --- | --- | --- |
| 오목 | `OmokReplayWriter` v1 — 헤더(players[]) + 착수 `{t,p,x,y}` | 착수 목록을 순서대로 되짚는다 |
| 장애물피하기 | `DodgeReplayWriter` v2 — 시드 + 틱별 입력 + 틱별 이탈 | `dodge-engine` 으로 같은 틱 로직을 다시 돌린다 |

둘 다 **못 읽으면 던진다.** 헤더 버전이나 규칙 상수가 어긋나는 기보를 자기 상수로 억지로 그리면
예외도 경고도 없이 "다른 게임"이 재생된다.

## 로그인 후 복귀

초대 링크를 받은 사람이 로그인해야 할 때, 로그인만 시키고 홈에 버려두면 방까지 다시 걸어와야
한다. `lib/auth-redirect.ts` 가 목적지를 두 경로로 나른다.

1. 같은 탭에서 끝나는 경로 — `/login?next=<path>` 쿼리 파라미터.
2. Google OAuth 처럼 사이트를 떠났다 돌아오는 경로 — 쿼리가 살아남지 못하므로 `sessionStorage`.
   **키는 서버가 발급한 OAuth `state`** 다. 시도마다 새로 만들어지는 불투명 값이 콜백 URL 로
   그대로 돌아오므로 "이 왕복"과 "그 목적지"가 1:1 로 묶인다 — 버려진 항목이 한참 뒤의 무관한
   로그인을 납치하지 못한다. TTL 은 서버 state TTL(600초)과 맞춘다.

`next` 는 사용자가 고쳐 쓸 수 있는 URL 에서 오므로 `sanitizeNextPath` 를 반드시 거친다. 통과
조건은 **같은 오리진의 절대 경로** 하나이고, 점 세그먼트를 지운 뒤의 경로까지 확인한다
(`/..//evil.example` 은 정규화하면 `//evil.example` 이 된다). 거절한 것은 전부 홈으로 떨군다.

## API 호출 규칙

- API 호출 공통 로직은 `front/lib/api.ts`에, 응답 타입은 `front/lib/types.ts`에 둔다.
- 응답의 성공/실패는 백엔드 `ApiResponse` 규약(`header.isSuccessful`/`header.message`)으로 판정한다.
- access token과 refresh token, `memberId`, `role` 은 `localStorage`에 저장한다.
- 인증 만료 시 `POST /api/auth/refresh-tokens`로 refresh를 시도하고 실패하면 토큰을 제거한다.
  예외는 `gameAPI.me()` 하나다(위 [게임 참가와 신원](#게임-참가와-신원) 참고).
- Google 인증은 Authorization Code + PKCE 흐름을 따른다. 로그인/회원가입 시작 API에서
  `authorizationUrl` 과 `state` 를 받아 이동하고, callback 페이지에서 `code`와 `state`를
  `POST /api/auth/callback-google`로 교환한다.
- 요청에는 언어 정보(`ko-KR`, `en-US`)를 포함한다.
- 홈 화면은 `next-themes` 기반 다크모드/라이트모드 전환을 제공한다.

## 컴포넌트 규칙

- 페이지는 `app/`에 두고, 재사용 가능한 화면 단위는 `components/`에 둔다.
- **판단은 컴포넌트에 두지 않는다** — 위 [판단은 React 밖 모듈에 둔다](#판단은-react-밖-모듈에-둔다).
- 버튼, 입력, 다이얼로그, 드롭다운 등은 `components/ui/`의 기존 컴포넌트를 우선 사용한다.
- 새 아이콘은 가능하면 `lucide-react`를 사용한다.
- `useSearchParams` 를 쓰는 클라이언트 컴포넌트는 `Suspense` 경계 안에 둔다.
- 헤더의 사이드바 토글·검색창은 `hooks/use-header-controls.tsx` 컨텍스트로 등록한다. 등록한
  화면(`/blog`)에서만 그 컨트롤이 나오고, 다른 라우트에서는 탭 세 개만 남는다.
- UI 텍스트는 사용자가 수행할 행동을 기준으로 짧게 작성한다.

## 스타일 규칙

- Tailwind utility를 우선 사용한다.
- 공통 스타일이 반복되면 작은 컴포넌트로 분리한다.
- 과도한 카드 중첩과 장식성 레이아웃을 피한다.
- 블로그 화면은 콘텐츠 탐색과 가독성을, 게임 화면은 판과 명단의 가시성을 우선한다.

## 빌드와 검증

```bash
cd front
npm test          # tsc --noEmit → vitest run  (274 tests / 9 files)
npm run build
```

- `npm test` 는 **타입 검사를 먼저** 돌리고 통과해야 vitest 로 넘어간다. 타입만 보려면
  `npm run typecheck`, 테스트만 보려면 `npm run test:unit`.
- **`next.config.mjs` 가 `typescript.ignoreBuildErrors` 를 켜 두었으므로 `npm run build` 만으로는
  타입 검사가 되지 않는다.** `npm test`(또는 `npx tsc --noEmit`)를 반드시 함께 돌린다. 한때
  레포 어디서도 `tsc` 가 돌지 않아 `@ts-expect-error` 핀이 아무 검증도 받지 못한 적이 있다.
- `vitest.config.ts` 는 `include` 가 아니라 `exclude` 를 쓴다. `lib/**` 로 좁혀 두면 나중에
  누군가 `components/` 나 `hooks/` 에 스펙을 두었을 때 러너가 **말없이 건너뛴다** — 초록인데
  아무것도 돌지 않는 상태가 가장 나쁘다.
- 테스트 환경은 기본값 `node` 다. 대상이 전부 React-free 모듈이고, `sessionStorage` 가 필요한
  곳은 테스트가 직접 최소 스텁을 세운다.
- `npm run lint`는 ESLint 초기 설정 프롬프트가 뜰 수 있으므로 자동 검증에 포함하지 않는다.

## 환경변수

`front/.env.local.example` 참조.

| 변수 | 용도 |
| --- | --- |
| `MVC_ORIGIN` | rewrites 가 `/api/auth`·`/api/back` 을 보낼 곳 (기본 `http://localhost:8000`) |
| `WEBFLUX_ORIGIN` | rewrites 가 `/api/game` 을 보낼 곳 (기본 `http://localhost:8001`) |
| `NEXT_PUBLIC_API_BASE_URL` | 브라우저가 백엔드를 직접 호출할 때만. 비어 있으면 동일 오리진 + rewrites |
| `NEXT_PUBLIC_WS_BASE_URL` | WebSocket 오리진. rewrites 를 타지 못하므로 별도로 필요 |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Google OAuth 클라이언트 ID |

## 알려진 주의사항

- `next/font`가 Google Fonts를 조회하므로 네트워크가 막힌 환경에서는 `npm run build`가 실패할 수 있다.
- `API_BASE_URL`(`lib/api.ts`)은 `NEXT_PUBLIC_API_BASE_URL`이 없으면 빈 문자열(`??  ""`)을
  쓴다 — `http://localhost:8000`으로 폴백하지 않는다. 동일 오리진 + rewrites로 요청이 나가고,
  개발 환경에서는 그 오리진이 결국 `:3000`의 Next 서버이므로 rewrites가 `MVC_ORIGIN`
  (`http://localhost:8000`)으로 넘겨준다.
- 서버는 소켓 `JOIN` 응답으로 내 `participantId` 를 알려주지 않는다. 화면은 회원/게스트 규칙으로
  계산하고 명단에 있는지 대조한다 — 서버가 `participantId` 를 실어 주면 이 계산을 없앨 수 있다.
- 마이페이지는 프로필을 **표시만** 한다. presigned PUT 업로드 화면은 미구현이다.
