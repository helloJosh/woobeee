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
|-- components/ui/       공통 UI primitive
|-- hooks/               React 컨텍스트와 훅
|-- lib/                 API, 타입, 에러 처리, 그리고 **React 밖으로 뺀 판단 모듈**
|-- lib/*.test.ts        위 판단 모듈의 vitest 스펙 (컴포넌트 옆이 아니라 대상 옆에 둔다)
|-- public/              정적 리소스
|-- vitest.config.ts     테스트 러너 설정
`-- package.json         프론트 빌드/실행/테스트 스크립트
```

## 라우팅

상단탭은 HOME / RESUME(모달) / 일정(로그인 시)이다. 마이페이지는 탭이 아니라 헤더 오른쪽의 **프로필 아바타**로
들어간다(로그인 상태에서만 보인다) — 아바타가 이미 그 자리를 가리키므로 탭과 중복이었다.
홈(`/`)이 곧 기술블로그 목록이다.
`Header` 는 루트 레이아웃(`app/layout.tsx`)에서 마운트되므로 모든 라우트에 나온다.

| 경로 | 화면 |
| --- | --- |
| `/` | 홈 — 기술블로그 목록 |
| `/blog`, `/blog/[postId]` | `/blog` 는 `/` 로 리다이렉트, 상세 |
| `/mypage` | 프로필 + 내가 참가한 공동구매 (회원 전용) |
| `/schedule` | 일정 (로그인 시) |
| `/login`, `/signup`, `/logout` | 인증 |
| `/auth/google/callback` | Google authorization callback 처리 |

`app/products`, `app/cart`, `app/chat` 은 폐기된 product/cart 백엔드를 호출한다. 살아 있는
화면에서 이 세 경로로 들어가는 진입 링크는 걷어냈지만, 폐기 화면끼리는 아직 서로 링크한다
(`app/products/[productId]/page.tsx` 의 상단 바가 여전히 `/cart` 로 이어진다). 페이지 삭제는
후속 과제다 — **이 세 경로의 규칙은 더 이상 유효하지 않다.**

## 판단은 React 밖 모듈에 둔다

이 프론트엔드의 핵심 규약이다. **화면 상태를 정하는 판단은 컴포넌트가 아니라 `lib/` 의
React-free 모듈에 두고, 컴포넌트는 그 결과를 그리기만 한다.**

| 모듈 | 담는 판단 |
| --- | --- |
| `lib/auth-redirect.ts` | 로그인 후 복귀 경로 — `next` 살균, 링크 조립, OAuth 왕복 보관 |
| `lib/blog-admin.ts` | 블로그 글 관리 — ADMIN 판정(`canManagePosts`), 초안 검증, multipart 조립, 카테고리 트리 평탄화, 드롭 이미지 보류·`${파일명}` 치환(`resolvePendingImages`), 마크다운 편집기 커서 삽입·드롭 이미지 수집(`insertSnippet`/`collectDroppedImages`) |
| `lib/markdown-url.ts` | 마크다운 렌더 URL 판정 — 기본 sanitizer 에 `blob:`(드롭 이미지 미리보기)만 추가 허용 |
| `lib/markdown-blank-lines.ts` | 연속 빈 줄 보존 — 여분 빈 줄을 `&nbsp;` 문단으로 살리되 코드 펜스 안은 보존 |

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
- 예외 하나: **Tailwind 클래스 문자열은 `components/` 에 둔다.** `tailwind.config.ts` 의
  content 글롭이 `app/`·`components/` 만 훑고 `lib/` 는 훑지 않아서, `lib/` 로 옮기면 클래스가
  생성되지 않는다. `lib` 쪽은 색 *인덱스*만 계산한다.
- `switch` 로 유니온을 다룰 때는 `default: assertNever(x)` 를 둔다. 없으면 새 분기가 추가돼도
  컴파일러가 조용히 넘어간다.

## 로그인 후 복귀

보호된 화면에 들어온 사람이 로그인해야 할 때, 로그인만 시키고 홈에 버려두면 원래 화면까지 다시 걸어와야
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
  화면(`/blog`)에서만 그 컨트롤이 나오고, 다른 라우트에서는 탭만 남는다.
- UI 텍스트는 사용자가 수행할 행동을 기준으로 짧게 작성한다.

## 스타일 규칙

- Tailwind utility를 우선 사용한다.
- 공통 스타일이 반복되면 작은 컴포넌트로 분리한다.
- 과도한 카드 중첩과 장식성 레이아웃을 피한다.
- 블로그 화면은 콘텐츠 탐색과 가독성을 우선한다.

## 빌드와 검증

```bash
cd front
npm test          # tsc --noEmit → vitest run  (240 tests / 12 files)
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
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Google OAuth 클라이언트 ID |

## 알려진 주의사항

- `next/font`가 Google Fonts를 조회하므로 네트워크가 막힌 환경에서는 `npm run build`가 실패할 수 있다.
- `API_BASE_URL`(`lib/api.ts`)은 빈 문자열 상수다 — 브라우저는 백엔드를 직접 호출하지 않고
  `http://localhost:8000`으로 폴백하지도 않는다. 동일 오리진 + rewrites로 요청이 나가고,
  개발 환경에서는 그 오리진이 결국 `:3000`의 Next 서버이므로 rewrites가 `MVC_ORIGIN`
  (`http://localhost:8000`)으로 넘겨준다.
- 마이페이지의 아바타가 곧 업로드 표면이다 — 클릭하면 파일 선택, 드롭도 받는다. 업로드·조회는
  둘 다 앱을 거친다(`POST`/`GET /api/auth/me/profile-image`). presigned 방식은 URL 의 호스트가
  서버용 `S3_ENDPOINT` 에서 나와 브라우저가 열 수 없어서 걷어냈다. 표시는 `AuthProvider` 가
  blob URL 을 한 번 받아 헤더 아바타와 마이페이지가 공유한다.
