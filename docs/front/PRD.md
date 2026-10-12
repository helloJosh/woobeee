# PRD — front (프론트엔드)

`front`는 백엔드 API를 사용해 사용자 화면을 제공하는 Next.js 14 애플리케이션이다. 이 문서는 화면이 제공해야 할 **제품 목표·사용자 여정·범위**를 정의한다. 디렉터리 구조, API 호출/토큰 규칙, 스타일·컴포넌트 규칙 등 구현 기준은 [`../FRONTEND.md`](../FRONTEND.md)에 있다.

- 코드: `front/`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 홈(`/`)은 기술블로그다 — 방문자가 첫 화면에서 글을 읽기 시작한다.
- 회원이 자기 프로필을 보고 일정을 관리한다.
- 사용자가 Google 인증으로 가입·로그인하고, 로그인 뒤 **원래 가려던 곳으로 돌아온다**.

## 상단탭

HOME(`/`) / RESUME(이력서 모달, 라우트 없음) / 일정(`/schedule`, 로그인 시) — 버튼·아이콘 없이 글씨만, 현재 위치는 진하게. 검색창은 헤더 정가운데(세 구역 격자)이고 `md` 미만(휴대폰)에서는 숨긴다 — 자리가 없어 탭을 밀어냈다. 헤더의 GitHub·이메일 아이콘도 뺐다. `/blog` 는 옛 링크를 위해 남아 있고
`category`·`search`·`tag` 쿼리를 그대로 들고 `/` 로 리다이렉트한다(`lib/blog-redirect.ts`). 글 상세는
`/blog/{id}`, 글쓰기는 `/blog/write` 그대로다. 마이페이지(`/mypage`)는 탭이 아니다: 헤더 오른쪽의 프로필 아바타가 그 자리를 가리키므로 탭은 중복이었다(아바타는
로그인 상태에서만 보인다 — 비로그인에게 마이페이지 입구가 필요 없다).
헤더는 루트 레이아웃에서 마운트되므로 모든 라우트에 나온다.

## 사용자 여정

### 인증
- 로그인/회원가입 시작 화면에서 백엔드의 시작 API로 `authorizationUrl`과 `state`를 받아 Google로 이동한다.
- **로그인으로 들어와도 계정이 없으면 서버가 콜백에서 바로 회원을 만들어 로그인시킨다**(AUTH-AC-21). 회원가입 화면은 닉네임을 직접 정하고 싶은 사람을 위한 선택 경로다 — 로그인 화면이 그렇게 안내한다.
- `/auth/google/callback`에서 `code`/`state`를 `POST /api/auth/callback-google`로 교환해 토큰을 받는다(Authorization Code + PKCE).
- **로그인 뒤에는 원래 가려던 곳으로 돌아간다.** 보호된 화면을 열었다가 로그인이 필요해진 사람을
  홈에 버려두면 다시 걸어와야 한다. 같은 탭 안에서 끝나는 경로는 `?next=`로, Google 왕복은
  OAuth `state`를 키로 한 `sessionStorage` 항목으로 목적지를 나른다. 돌아갈 곳이 없으면 홈(`/`).
- `next` 값은 사용자가 고쳐 쓸 수 있는 URL에서 오므로 반드시 살균한다. 통과 조건은 같은 오리진의
  절대 경로 하나이고, 거절한 것은 전부 홈으로 떨군다.

### 홈 — 기술블로그 (`/`)
- 헤더 바로 아래 전체 폭 소개 배너(「woobeeeblog / 백엔드엔지니어 김병우 / 경험한 것을 기록합니다」 — 테마 팔레트로 찍은 두 장 `public/home-banner-light.png`/`-dark.png` 를 `.dark` 로 고른다, 없으면 같은 문구의 CSS 배너). 장식용이고 클릭 동작은 없다. 이력서는 헤더 RESUME 탭이 모달로 연다 — PDF 뷰어 UI 없이 렌더된 이미지(`public/resume.png`, `scripts/render-resume.sh`) 한 장만(다운로드 링크 없음). 이력서 PDF 도 레포에 포함돼 있다(사용자 결정).
- 우아한형제들 기술블로그의 두 열을 따른다(스펙 `docs/superpowers/specs/2026-09-09-blog-home-tags-design.md`). 맨 위에는 인기 태그 50개가 둥근 알약 줄로 가운데 정렬돼 있고(첫 칸 「-」 = 전체) 누르면 그 태그로 필터한다.
  왼쪽은 글을 세로로 — 날짜·카테고리명, 큰 제목, 작성자가 적은 설명(없으면 생략), 구분선, 무한 스크롤. 글 아래 태그 표시는 없다(태그는 상단 알약 줄·사이드바에서만). 이미지도 본문 요약도 없다.
  오른쪽(`lg` 이상)은 「카테고리」와 「태그」. 휴대폰 폭에서는 태그 줄과 사이드바를 숨기고 목록만 보이며, 「≡ 카테고리 · 태그」 버튼이 오른쪽 서랍으로 같은 내용을 연다. 카테고리는 헤더를 눌러 접고 펼치며 **기본 접힘**
  (브라우저에 기억), 펼치면 전체·부모(글 수)·선택된 부모의 자식이 나온다(`lib/category-nav.ts`). 태그는
  `GET /api/back/tags` 를 `이름 (글 수)` 목록으로.
- 필터(`category`·`search`·`tag`)는 전부 URL 쿼리에 산다. 헤더 검색은 이 페이지가 마운트돼 있을 때만
  헤더에 나타난다(`useRegisterHeaderControls`). 태그 chip 클릭은 그 태그로 필터하고 다시 누르면 푼다.
- 홈에 「글쓰기」 버튼, 글 상세에 「수정」·「삭제」 버튼은 없다 — ADMIN 은 `/blog/write`·`/blog/edit/{id}` URL 로 바로 들어가고, 삭제는 DB 에서 직접 한다(사용자 결정). 화면 게이팅이 없어졌으므로 진짜 방어는 서버 403 하나다. 에디터는 태그를 chips 로 입력한다(Enter·쉼표 추가, 최대 10개·30자 —
  서버 `TagNormalizer` 와 같은 규칙, `lib/blog-admin.ts`).

### 마이페이지 (`/mypage`)
- 회원 전용이다. 비로그인이면 여기로 돌아오는 `next`를 달아 로그인으로 보낸다.
- 프로필(닉네임·이메일·프로필 이미지)을 표시한다. 게임 결과·기보·게임머니는 game 도메인과 함께 제거돼 더는 없다.
- 「내가 참가한 공동구매」 목록을 보인다(`GET /api/back/pokemon/orders/me`, POKEMON-AC-36). 차수 제목은 `/pokemon/{handle}?round=N`, 신청서 상태 배지는 `/pokemon/orders/{id}` 로 잇는다. 요약 줄은 진행 중·전달완료·취소 건수와 **입금할 금액**(`ORDERED` 신청서의 `transferKrw` 합)이다 — 취소는 진행 중·입금할 금액에서 뺀다.

### 블로그
- `/blog` 목록, `/blog/[postId]` 상세를 제공한다.
- 헤더의 카테고리 사이드바 토글과 검색창은 이 화면이 등록할 때만 나온다.
- 요청에 언어 정보(`ko-KR`/`en-US`)를 포함해 다국어 콘텐츠를 표시한다([`../blog/adr/ADR-002-i18n.md`](../blog/adr/ADR-002-i18n.md)).

## 인증 상태 처리

- access/refresh token, `memberId`, `role`을 `localStorage`에 저장한다.
- 인증 만료 시 `POST /api/auth/refresh-tokens`로 재발급을 시도하고, 실패하면 토큰을 제거한다.
- 역할은 `ROLE_MEMBER` 하나뿐이라 역할 기반 UI 분기는 없다([`../auth/adr/ADR-003-member.md`](../auth/adr/ADR-003-member.md)).

## 범위와 제약

- 현재 화면 범위: 홈(블로그 목록), 일정, 마이페이지(프로필 + 내가 참가한 공동구매),
  블로그 상세·글쓰기, 로그인·회원가입·로그아웃, Google callback.
- **폐기 화면**: `app/products`, `app/cart`, `app/chat` 은 폐기된 product/cart 백엔드를 호출한다.
  살아 있는 화면(홈 등)에서 이 세 경로로 들어가는 진입 링크는 걷어냈다. 다만 폐기 화면끼리는
  아직 서로 링크한다 — `app/products/[productId]/page.tsx` 의 상단 바가 여전히 `/cart` 로
  가는 링크를 그린다. 페이지 삭제는 후속 과제다.
- 미구현: 프로필 이미지 업로드 UI(표시만 한다).
- 다크/라이트 모드 전환(`next-themes`)을 제공한다. 두 테마의 팔레트는 `docs/DESIGN.md` 의 표(라이트 `#F9F7F7/#DBE2EF/#3F72AF/#112D4E`, 다크 `#1B262C/#0F4C75/#3282B8/#BBE1FA`)가 기준이고 `globals.css` 토큰이 단일 출처다.
- 검증은 `cd front && npm test && npm run build`. `next/font`의 Google Fonts 조회로 네트워크 차단
  시 빌드가 실패할 수 있다.

## 비기능 요구사항

- API 응답은 백엔드의 `ApiResponse` 규약(`header.isSuccessful`/`message`)을 기준으로 성공/실패를 처리한다([`../_common/adr/ADR-002-apiresponse.md`](../_common/adr/ADR-002-apiresponse.md)).
- 공통 호출 로직은 `lib/api.ts`, 타입은 `lib/types.ts`로 모은다.
- **화면 상태를 정하는 판단은 컴포넌트가 아니라 `lib/` 의 React-free 모듈에 두고 테스트로 고정한다.**
  근거와 목록은 [`../FRONTEND.md`](../FRONTEND.md).
- UI는 `components/ui/`의 기존 컴포넌트를 우선 재사용한다.
