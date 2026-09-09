# 블로그 홈 · 게임 허브 이동 · 태그 — 설계

날짜: 2026-09-09. 상태: 승인됨(채팅), 구현 진행.

## 배경

지금 `/` 는 게임 허브(`components/game/game-hub.tsx`)고 `/game` 은 `/` 로 리다이렉트한다. 기술블로그는
`/blog` 에 있고 왼쪽 카테고리 트리 사이드바 + 세로 카드 리스트다. 사용자는 홈을 **블로그**로 되돌리고
게임을 `/game` 으로 내리며, 홈 화면은 우아한형제들 기술블로그(https://techblog.woowahan.com/)의
구성을 따르길 원한다. 카드에 붙는 **태그**도 함께 필요하다 — 지금 글에는 태그가 없다.

## 결정

### A. 라우트

| 경로 | 전 | 후 |
| --- | --- | --- |
| `/` | 게임 허브 | **블로그 홈**(`components/home/home-page.tsx`) |
| `/blog` | 블로그 목록 | `/` 로 **307** 리다이렉트 — `category`·`search`·`tag` 쿼리만 옮긴다(`front/middleware.ts`, matcher `/blog`). 페이지 안의 `redirect()` 는 레이아웃 스트리밍 뒤라 meta refresh 로 떨어져 크롤러가 200 을 보기 때문에 미들웨어로 뺐다 |
| `/blog/[id]`, `/blog/write` | 그대로 | 그대로 |
| `/game` | `/` 로 리다이렉트 | **게임 허브** |

- 헤더 탭: 홈 · 게임 · 일정(로그인 시). 마이페이지는 아바타로 유지.
- 로그인 뒤 기본 복귀(`/`)와 `auth-redirect` 규칙은 그대로 — 홈이 블로그가 됐을 뿐이다.
- 방 화면의 「게임 목록으로」와 비로그인 방 만들기의 `next=/game` 은 이미 `/game` 을 가리킨다.

### B. 홈 화면

> 2026-09-09 2차 — 사용자가 실제 우아한 홈 캡처를 보여 주며 **이미지 없는 두 열**로 바꿨다(1차의 히어로·카드
> 그리드·상단 chips 는 폐기). 카테고리는 "옆에 숨기고 접을 수 있게".

0. **상단 태그 알약 줄**(`tag-bar.tsx`, 3차) — 사용자가 우아한 홈의 상단 알약 내비를 가리키며 "태그로 구분"을 요청. 인기 태그 50개를 작은 알약(text-xs)으로 **글 열 안에서** 가운데 정렬·줄바꿈(사이드바 폭에 끌리지 않게). 첫 칸 「-」 = 전체(태그 해제), 활성 태그는 채움. 사이드바 「태그」는 상위 20개에 글 수를 함께 보인다.
1. **왼쪽 — 글 세로 목록**(`post-list-item.tsx`): 한 글 = `2026. 09. 08.` + 카테고리명(작성자 필드가 없어 대신) →
   큰 제목(2xl/3xl, 링크) → 요약 2줄 → 태그 chips. 항목 사이 구분선. 무한 스크롤(`useInfinitePosts`, pageSize 10,
   `tag` 파라미터). 이미지·썸네일 없음.
2. **오른쪽 — 사이드바**(`home-sidebar.tsx`, `lg` 이상 240px·sticky, 모바일은 목록 위):
   - 「카테고리」 — 헤더 클릭으로 접고 펼침. **기본 접힘**, 상태는 `localStorage("home.categoriesOpen")`. 접힌 채로도
     선택된 카테고리 이름을 헤더 옆에 보인다. 펼치면 「전체」 + 부모(글 수), 선택된 부모 아래에만 자식 목록
     (`lib/category-nav.ts` 의 `activeParent`). 선택은 `?category=<id>`.
   - 「태그」 — `GET /api/back/tags?limit=20` 을 `이름 (글 수)` 세로 목록으로. 클릭 = `?tag=<name>`, 다시 클릭 = 해제.
3. **검색** — 기존 헤더 검색을 `useRegisterHeaderControls` 로 등록. `?search=`. 활성 필터(카테고리·검색·태그)는
   목록 위에 × 달린 chip 으로 보여 한 번에 푼다.
4. **요약** — `lib/post-preview.ts` 의 `excerpt(markdown, maxChars)`: 코드블록·이미지·링크 문법·헤딩·강조·HTML 을 걷어낸
   본문 앞부분(테스트). 썸네일 함수(`firstImageUrl`·`placeholderHue`)는 2차에서 삭제.
5. ADMIN 이면 목록 위 우측 「글쓰기」 버튼(`canManagePosts`).
6. 삭제: `components/blog-page.tsx`, `components/post-list.tsx`, `components/sidebar.tsx`. 헤더 컨트롤의 사이드바 토글은
   등록하는 곳이 없어지므로 헤더가 그리지 않는다.

### C. 태그 (백엔드)

- **스키마 V13** — FK 없음(사용자 결정: 참조 무결성은 애플리케이션이 검사한다).
  ```sql
  CREATE TABLE tags (id BIGINT IDENTITY PK, name VARCHAR(30) NOT NULL, created_at TIMESTAMP(6) NOT NULL);
  CREATE UNIQUE INDEX ux_tags_name_lower ON tags (lower(name));
  CREATE TABLE post_tags (id BIGINT IDENTITY PK, post_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, UNIQUE (post_id, tag_id));
  CREATE INDEX idx_post_tags_tag_id ON post_tags (tag_id);
  ```
- **입력** — `PostPostRequest.tags: List<String>`(선택). 규칙: 각 항목 trim, 빈 문자열 제거, 대소문자 무시 중복 제거, 최대 10개, 각 1~30자. 위반은 400(`ErrorCode` 의 기존 BAD_REQUEST 계열).
- **저장/수정** — 이름을 lower 로 비교해 있는 태그는 재사용, 없는 이름은 `tags` 에 생성(표기는 입력 그대로 보존). `post_tags` 는 집합 교체(삭제 후 재생성). 글 삭제 시 `post_tags` 먼저 삭제. 글 없는 태그 행은 남겨도 무해(인기 태그는 글 수 > 0 만 낸다).
- **응답** — 목록 `PostContent.tags`, 상세 `GetPostResponse.tags`: `[{id, name}]`. 목록은 글 id 를 모아 한 번의 조인 조회(N+1 금지).
- **필터** — `GET /api/back/posts?tag=<name>`(대소문자 무시). 카테고리·검색과 AND.
- **인기 태그** — `GET /api/back/tags?limit=20`: `[{id, name, count}]` 글 수 내림차순, 같은 수는 이름 순. 공개 GET.
- **QueryDSL 제거** — `PostQueryRepositoryImpl.searchPosts` 에 태그 조건을 넣는 대신 네이티브 SQL 로 다시 쓴다. `countGroupByCategoryId` 도 함께. `QuerydslConfig`, `querydsl-jpa`/`querydsl-apt` 의존, 컨텍스트 테스트의 `JPAQueryFactory` 목을 걷어낸다. 정렬·검색 대상 컬럼 규칙(BLOG-AC-01/02/03)은 그대로.

### C-2. 글 설명 (4차 추가)

사용자 요청: "글 description 을 적어둘 수 있게, 제목 아래 description 이 나오게". 제목과 같은 언어별 필드
`description_ko/description_en VARCHAR(300)`(V14). 요청 `descriptionKo/descriptionEn`(선택), 빈 값은 null.
응답 `description` 은 locale 의 것, 영어가 없으면 한국어. 목록 항목과 상세는 제목 아래에 설명을 보이고,
목록은 설명이 없으면 본문 요약(`summaryOf`)으로 대체한다. 에디터는 각 언어 탭의 제목 아래 한 줄 입력.

### D. 에디터

태그 입력 한 줄: Enter 또는 쉼표로 chip 추가, × 로 제거, 최대 10개 안내. 수정 화면은 상세 응답의 `tags` 로 초기화. `request` JSON 파트에 `tags` 를 싣는다.

### E. 문서·테스트

- `docs/blog/PRD.md`: 모델·엔드포인트·동작 규칙에 태그 추가, AC 표에 BLOG-AC-18~22.
  - 18 태그 정규화·상한 / 19 저장·수정 집합 교체와 재사용 / 20 목록·상세 응답 + 배치 조회 / 21 `tag` 필터 / 22 인기 태그 정렬.
  - 기존 BLOG-AC-01~04 는 네이티브 전환과 함께 실 Postgres 테스트로 처음 고정된다.
- `docs/front/PRD.md`: 상단탭·홈·게임 메인 경로. `docs/api/README.md`: 새 파라미터·엔드포인트. `CLAUDE.md`: QueryDSL 잔존 항목 삭제, 엔드포인트 표.
- 프론트 테스트: `lib/post-preview.test.ts`, `lib/blog-redirect.test.ts`(쿼리 보존), `lib/category-nav.test.ts`(부모/자식 chips 계산).

## 범위 밖

- 태그 이름 변경·병합 관리 UI. 태그 전용 페이지(`/tags/…`). 글에 별도 썸네일·요약 필드.
- 폐기 화면(`app/products`, `app/cart`, `app/chat`) 정리는 그대로 후속 과제.
