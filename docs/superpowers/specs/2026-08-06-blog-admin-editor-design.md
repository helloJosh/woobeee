# 블로그 ADMIN 편집 권한 + 노션풍 에디터 + API 권한 문서 설계

- 날짜: 2026-08-06
- 상태: 승인됨 (사용자: "실시간 협업은 필요없고 1,2번 해주고 docs 밑에 api로 정리")
- 전제: 회원 role(`ROLE_MEMBER`/`ROLE_ADMIN`)은 2026-08-05 설계로 이미 저장·토큰 반영됨

## 1. blog 쓰기 ADMIN 게이트 + loginId 위조 구멍 봉합

### 현재 문제

- blog·profile-image 엔드포인트는 신원을 `loginId` 요청 헤더(이메일)로 받는다.
- `AccessTokenLoginIdHeaderFilter` 는 **클라이언트가 `loginId` 헤더를 보내면 검증 없이
  통과**시킨다(헤더가 있으면 토큰 확인 자체를 건너뜀). 누구나 타인 이메일로 글 작성·삭제·
  댓글·프로필 변경이 가능하다.
- 프론트는 `Authorization: Bearer` 만 보내므로(확인됨) 서버가 클라이언트발 `loginId` 를
  무시하도록 바꿔도 프론트 호환성 문제는 없다.

### 결정

- `AccessTokenLoginIdHeaderFilter` 를 다음과 같이 바꾼다:
  1. **클라이언트가 보낸 `loginId` 헤더는 항상 제거**한다. `loginId` 는 오직 유효한
     access token 에서만 파생된다.
  2. 토큰이 유효하면 `loginId` 헤더 주입 + 토큰의 `role` 을 request attribute 로 저장한다.
  3. **ADMIN 전용 쓰기 경로** — `POST/PUT/DELETE /api/back/posts/**`,
     `POST/DELETE /api/back/categories/**` — 에서 role 이 `ROLE_ADMIN` 이 아니면
     `403` + `ApiResponse` 실패 봉투로 즉시 응답한다.
  4. 경로·메서드 → ADMIN 필요 여부 판정은 package-private 정적 메서드로 분리해 단위
     테스트한다.
- 댓글·좋아요·프로필 이미지는 지금처럼 **로그인 회원이면 가능**(변경 없음 — 단 위조가
  막히므로 실질 보안은 올라간다).
- `PUT /api/back/posts/{postId}` 를 신설한다(에디터의 수정 저장용). POST 와 같은
  multipart 계약(`request` JSON + `markdownKr`/`markdownEn` + `file`*). 서비스는 제목·
  카테고리·본문을 갱신하고 새 첨부는 기존 `{postId}/` prefix 로 업로드한다. 작성자 본인
  확인(deletePost 와 동일)을 유지한다.

### AC (docs/blog/PRD.md 에 표 신설)

| ID | 기준 |
| --- | --- |
| BLOG-AC-01 | 클라이언트가 보낸 `loginId` 헤더는 무시된다 — 토큰 없이 위조 헤더만으로는 인증되지 않는다 |
| BLOG-AC-02 | `ROLE_MEMBER` 토큰으로 게시글/카테고리 쓰기(POST/PUT/DELETE)를 호출하면 `403` |
| BLOG-AC-03 | `ROLE_ADMIN` 토큰으로 게시글 쓰기를 호출하면 통과한다 |
| BLOG-AC-04 | 토큰 없이 게시글 쓰기를 호출하면 `403` |
| BLOG-AC-05 | `PUT /api/back/posts/{postId}` 는 제목·본문·카테고리를 갱신한다 |
| BLOG-AC-06 | 댓글/좋아요는 로그인 회원(`ROLE_MEMBER`)이면 가능하다 |

## 2. ADMIN 전용 노션풍 에디터 (front)

- **라이브러리**: BlockNote (`@blocknote/core` + `@blocknote/react` + `@blocknote/mantine`).
  노션형 UX(슬래시 명령, 블록 드래그) 기본 제공, 마크다운 왕복 API
  (`tryParseMarkdownToBlocks` / `blocksToMarkdownLossy`) 보유. 저장 형식은 기존 계약
  그대로 마크다운.
- **라우트**: `app/blog/write/page.tsx`(신규 작성), `app/blog/edit/[postId]/page.tsx`(수정).
  클라이언트 컴포넌트, BlockNote 는 `dynamic(..., { ssr: false })`.
- **게이팅**: 로그인 응답의 role 이 이미 `localStorage.authRole` 에 저장된다. 판단은
  `front/lib/blog-admin.ts` 의 React-free 함수로 둔다:
  - `canManagePosts(role)` — `ROLE_ADMIN` 만 true
  - `validatePostDraft(draft)` — 제목/카테고리/본문 필수 검증
  - `buildPostFormData(draft)` — multipart 조립(`request` JSON blob + `markdownKr`/`markdownEn`)
  - 전부 `lib/blog-admin.test.ts` 로 고정 (vitest node 환경, FormData/Blob 은 Node 18+ 내장)
- **UI 노출**: 블로그 목록에 ADMIN 일 때만 "글쓰기" 버튼, 상세에 ADMIN 일 때만 수정/삭제
  버튼. 서버 403 이 진짜 방어이고 UI 게이팅은 UX 다.
- **API 클라이언트**: `lib/api.ts` 에 `createPost`/`updatePost`/`deletePost` 추가.
  multipart 전송 시 Content-Type 은 브라우저가 boundary 포함해 설정하도록 명시하지 않는다.
- ~~**이미지 v1 범위 밖**~~ → **개정 (2026-08-11)**: 드래그앤드롭 이미지 업로드를 구현했다.
  - BlockNote `uploadFile` 훅이 드롭/붙여넣기된 파일을 **즉시 올리지 않고 메모리에 보관**하고
    blob URL 로 미리보기만 그린다 — 글 생성 전에는 postId 가 없어 올릴 곳이 없기 때문.
  - 저장 시 `resolvePendingImages` 가 본문의 blob URL 을 `${파일명}` 플레이스홀더로 치환하고,
    **본문에서 실제로 쓰인 이미지만** 같은 multipart 의 `file` 파트로 싣는다(드롭했다 지운
    이미지는 전송하지 않음). 파일명은 `uniqueFileName` 으로 공백 제거·중복 회피.
  - 서버는 기존 계약 그대로 `{postId}/{파일명}` 으로 S3 에 올리고 조회 시 플레이스홀더를
    공개 URL 로 치환한다 — **백엔드 코드 변경 없음**. 단 multipart 크기 기본값(파일당 1MB)이
    사진을 못 받아 `spring.servlet.multipart` 를 500MB/1GB 로 올렸다(쓰기가 ADMIN 전용이라
    남용 면이 좁다는 판단, 사용자 결정).
  - 기존 글의 공개 URL 이미지는 왕복 시 URL 로 보존된다. `${파일명}` 플레이스홀더는 서버가
    조회 시 치환해 주므로 수정 왕복 후에는 공개 URL 로 고정된다(허용).

## 3. API 권한 문서 (`docs/api/README.md`)

- app-mvc(auth/blog)·app-webflux(game) 의 전체 HTTP 엔드포인트 + WebSocket 을 한 문서로.
- 컬럼: 메서드/경로/설명/**권한**(`공개`, `로그인`, `ADMIN`, 참가자 본인 등)/비고.
- 권한 모델 요약: `ROLE_MEMBER`/`ROLE_ADMIN`, 토큰 계약(`TokenMetadata`), `loginId` 헤더는
  서버 내부 주입 전용(클라이언트발 값은 무시됨)임을 명시.
- 동작이 바뀌면 이 문서를 함께 갱신한다는 규칙 한 줄 포함.

## 범위 밖

- 실시간 협업(CRDT)·동시 편집 — 필요 없음 확인
- 에디터 내 이미지 업로드, EN 자동 번역
- blog 도메인 전체 AC 백필(이번에는 권한 관련 6줄만 신설)
