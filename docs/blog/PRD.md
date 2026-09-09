# PRD — blog (기술 블로그)

`blog` 도메인은 게시글, 카테고리, 태그, 댓글, 좋아요 기능을 담당한다.

- 베이스 경로: `/api/back/posts`, `/api/back/categories`, `/api/back/tags`, `/api/back/comments`, `/api/back/likes`
- 코드: `com.woobeee.mvc.blog`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 사용자가 게시글을 카테고리·검색어·태그·페이징으로 조회하고, 상세를 다국어(locale)로 본다.
- 로그인 사용자가 댓글(대댓글 포함)과 좋아요로 상호작용한다.

## 모델

- `Posts`(제목/본문 한국어·영어 필드 `titleKo/textKo`, `titleEn/textEn`, 한 줄 설명 `descriptionKo/descriptionEn`(V14, 선택·300자), `categoryId`, `createdAt`), `Categories`, `Tags`(`name`, lower 유니크), `PostTags`(글-태그 연결, V13), `Comments`(대댓글 구조), `Likes`.
- 저장소: 단순 CRUD는 Spring Data JPA(`PostRepository`, `CategoryRepository`, `TagRepository`, `PostTagRepository`, `CommentRepository`, `LikeRepository`), 검색/집계는 로우 쿼리(Native SQL). `PostQueryRepositoryImpl`(목록·카테고리 집계)은 2026-09-09 에 QueryDSL 에서 네이티브 SQL 로 전환했고 QueryDSL 의존은 제거됐다.
- 태그 테이블에는 FK 가 없다(사용자 결정). 참조 무결성은 서비스가 검사한다 — 글 삭제 시 `post_tags` 를 먼저 지운다.
- 서비스는 interface/impl 분리(`PostService`/`PostServiceImpl` 등).

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 |
| --- | --- |
| 게시글 목록 조회 | `GET /api/back/posts` |
| 게시글 상세 조회 | `GET /api/back/posts/{postId}` |
| 게시글 등록(멀티파트) | `POST /api/back/posts` |
| 게시글 삭제 | `DELETE /api/back/posts/{postId}` |
| 인기 태그 조회 | `GET /api/back/tags?limit=20` |
| 카테고리 생성 | `POST /api/back/categories/{parentId}` |
| 카테고리 삭제 | `DELETE /api/back/categories/{categoryId}` |
| 댓글 조회 | `GET /api/back/comments/{postId}` |
| 댓글 삭제 | `DELETE /api/back/comments/{commentId}` |
| 좋아요 등록 | `POST /api/back/likes/{postId}` |
| 좋아요 취소 | `DELETE /api/back/likes/{postId}` |

## 동작 규칙 (코드 기준)

- **기본 카테고리 시드**: `V3__default_categories.sql` 이 최상위 카테고리 여섯 개(백엔드 /
  프론트엔드 / 인프라 / 알고리즘 / 회고 / 기타, 각각 name_en 포함)를 심는다 — 새 DB 라도
  블로그가 빈 카테고리로 시작하지 않는다. 같은 이름이 이미 있으면 건너뛴다(WHERE NOT EXISTS).
  검증: `DefaultCategoriesSeedTest`(실 Postgres 필요).

- **목록/검색**: 카테고리 id 집합·검색어 `q`·태그 `tag`·`locale`·페이징으로 조회한다. 정렬은 `createdAt desc, id desc`. locale이 `en`이면 영어 제목/본문, 아니면 한국어 제목/본문을 대상으로 부분일치 검색한다(`PostQueryRepositoryImpl.searchPosts`, 네이티브 SQL — `ILIKE`, 와일드카드 이스케이프). 태그는 이름을 대소문자 무시로 `EXISTS` 서브쿼리로 건다. 상세: [`adr/ADR-001-postpaging.md`](adr/ADR-001-postpaging.md).
- **태그**: 글 저장·수정 요청의 `tags: string[]` 를 `TagNormalizer` 가 정규화한다(trim, 빈 값 제거, 대소문자 무시 중복 제거 — 첫 표기 유지, 최대 10개, 각 30자; 위반은 400 `post_invalidTags`). 이름을 소문자로 비교해 있는 태그는 재사용하고 없는 이름은 새로 만든다. 연결은 집합 교체. 목록·상세 응답에 `tags: [{id, name}]` — 목록은 글 id 를 모아 한 번에 조인 조회한다. 태그 전용 쓰기 API 는 없다(글쓰기 ADMIN 경로 안에서만 만들어진다).
- **카테고리 집계**: 카테고리별 게시글 수를 로우 쿼리 `GROUP BY`로 한 번에 집계한다(`countGroupByCategoryId`).
- **상세 조회**: locale과 로그인 정보를 반영한다.
- **댓글**: 댓글/대댓글(parent) 구조를 지원한다.
- **좋아요**: 로그인 사용자 기준으로 등록/취소한다.
- **작성자 식별**: `posts` / `comments` / `likes` 는 `member_id` 단독으로 작성자를 가리킨다. 회원이 단일
  `Member`로 통합돼 역할 구분이 없어졌으므로 `member_role` 컬럼은 제거했다. 작성자 동일성 검사도
  `memberId` 비교만 한다.
- 로그인 사용자 식별은 `AuthMemberResolver`로 해석한다(`MemberIdentity(memberId, loginId)`).

## 인수 기준 (Acceptance Criteria)

각 항목은 테스트로 커버한다(프로세스 규칙은 `CLAUDE.md`). 동작/계약 변경 시 이 표를 먼저 갱신하고 테스트를 함께 수정한다.

> AC-05·06(댓글·좋아요)은 아직 테스트가 없다. 목록·태그 쪽(AC-01~04, 18~22)은 실 Postgres 리포지토리 테스트와 서비스 테스트로 고정됐다.

| ID | 인수 기준 (Given–When–Then) | 커버 테스트 |
| --- | --- | --- |
| BLOG-AC-01 | 게시글 목록은 `createdAt DESC, id DESC`로 정렬해 반환한다(페이징·`hasNext` 포함) | `PostRepositoryTest`(실 Postgres) |
| BLOG-AC-02 | `locale=en`이면 영어 컬럼(`title_en/text_en`), 그 외에는 한국어 컬럼을 대상으로 대소문자 무시 부분일치 검색한다. 공백만인 검색어는 무시 | `PostRepositoryTest` |
| BLOG-AC-03 | 카테고리 id 집합이 주어지면 해당 카테고리 게시글만 조회한다(`IN`); null 이면 전체 | `PostRepositoryTest` |
| BLOG-AC-04 | 카테고리별 게시글 수를 `GROUP BY`로 집계해 반환한다. 글 없는 카테고리는 행이 없고 빈 입력은 빈 결과 | `PostRepositoryTest` |
| BLOG-AC-05 | 댓글은 댓글/대댓글(parent) 구조로 조회·생성된다 | 미작성 — 추가 필요 |
| BLOG-AC-06 | 좋아요는 로그인 사용자 기준으로 등록/취소(토글)된다 | 미작성 — 추가 필요 |
| BLOG-AC-07 | 클라이언트가 보낸 `loginId` 헤더는 무시된다 — 신원은 오직 유효한 access token 에서만 파생된다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-08 | **유효한** `ROLE_MEMBER` 토큰으로 게시글/카테고리 쓰기(POST/PUT/DELETE)를 호출하면 `403` + `ApiResponse` 실패 봉투를 반환한다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-09 | `ROLE_ADMIN` 토큰은 게시글/카테고리 쓰기를 통과한다. 읽기(GET)는 누구나 가능하다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-10 | 댓글/좋아요 쓰기는 로그인 회원(`ROLE_MEMBER`)이면 가능하다(ADMIN 불필요) | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-11 | `PUT /api/back/posts/{postId}` 는 작성자 본인일 때 제목·본문·카테고리를 갱신하고, 마크다운 파트가 없으면 본문을 보존한다 | `PostServiceImplTest` |
| BLOG-AC-12 | 무토큰 또는 만료(스토어에 없는) 토큰으로 게시글/카테고리 쓰기를 호출하면 `401` + `ApiResponse` 실패 봉투를 반환한다 — 권한 부족(403)과 구분해야 프론트가 refresh 후 재시도할 수 있다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-13 | 본문 이미지 URL 은 presigned URL 이고 presigner 가 준 문자열을 그대로 박는다(서명이 host·키를 포함하므로 후처리 금지). 서명하라고 넘기는 키는 `{postId}/{basename}` 으로, 플레이스홀더의 경로 성분은 제거한다 — 안 그러면 같은 버킷의 `profiles/` 를 여는 유효한 서명이 만들어진다 | `PostServiceImplTest` |
| BLOG-AC-14 | `${파일명}` 플레이스홀더는 저장 원문에 유지되고 치환은 조회 시점에만 일어난다 — 원문에 URL 을 구우면 서빙 방식이 바뀔 때 기존 글이 전부 깨진다 | `PostServiceImplTest` |
| BLOG-AC-15 | 목록 응답도 `${파일명}` 을 치환한다 — 치환이 상세에만 걸려 있으면 목록 미리보기에 원문이 그대로 나간다 | `PostServiceImplTest` |
| BLOG-AC-16 | `GET /api/back/posts/{postId}/images/{파일명}` 은 버킷을 공개하지 않고 앱 자격증명으로 오브젝트를 스트리밍한다. 파일명은 basename 만 남겨 `../` 로 같은 버킷의 `profiles/` 를 읽을 수 없고, 없는 오브젝트는 404 다 | `PostServiceImplTest` |
| BLOG-AC-17 | 수정 화면은 불러온 본문의 `/api/back/posts/{postId}/images/{파일명}` 을 `${파일명}` 으로 되돌린다 — 되돌리지 않으면 저장이 해석된 경로를 원문에 구워 AC-14 가 깨진다. 다른 글의 경로와 외부 URL 은 건드리지 않는다 | `blog-admin.test.ts` |
| BLOG-AC-18 | 태그 입력 정규화: trim, 빈 값 제거, 대소문자 무시 중복 제거(첫 표기 유지), 최대 10개, 각 1~30자 — 위반은 400 `post_invalidTags`. 프론트 `normalizeTags`/`validatePostDraft` 도 같은 규칙 | `TagNormalizerTest`, `blog-admin.test.ts` |
| BLOG-AC-19 | 저장·수정은 이름을 소문자로 비교해 있는 태그를 재사용하고 없는 이름을 새로 만든 뒤 `post_tags` 를 **집합 교체**한다(수정은 삭제 후 재생성). 글 삭제는 연결을 먼저 지운다. 태그 없이 저장하면 태그 저장소를 건드리지 않는다. 에디터는 기존 태그(인기순 200개)를 자동완성으로 보여 재사용을 유도하고(`tagSuggestions` — 대소문자 무시 부분일치, 고른 것 제외, 앞글자 일치 우선, 최대 8개), 없는 이름은 Enter 로 새 태그가 된다 | `PostServiceImplTest`, `PostRepositoryTest`, `blog-admin.test.ts` |
| BLOG-AC-20 | 목록 `PostContent.tags` 와 상세 `GetPostResponse.tags` 는 `[{id, name}]`(글 안에서 이름 순). 목록은 글 id 를 모아 **한 번의** 조인 조회로 붙인다 | `PostServiceImplTest`, `PostRepositoryTest` |
| BLOG-AC-21 | `GET /api/back/posts?tag=<name>` 은 대소문자를 무시하고 카테고리·검색과 AND 로 겹친다. 프론트는 `?tag=` 쿼리로 홈을 필터한다 | `PostRepositoryTest` |
| BLOG-AC-23 | 글 설명: `PostPostRequest.descriptionKo/descriptionEn`(선택, 각 300자)을 저장·수정 때 갈아 끼우고 빈 값은 null. 목록·상세 응답의 `description` 은 locale 의 것, 영어가 없으면 한국어로 대체(제목과 같은 규칙). 화면은 제목 아래에 설명을 보이고, 목록은 설명이 없으면 그 줄을 비운다(본문에서 요약을 뽑지 않는다) | `PostServiceImplTest`, `blog-admin.test.ts` |
| BLOG-AC-22 | `GET /api/back/tags?limit=N`(기본 20, 공개)은 글 수 내림차순·같으면 이름 오름차순으로 글 있는 태그만 낸다 | `PostRepositoryTest`, `TagControllerTest` |

## 지원 기능

- 페이징 보조: `CustomPageable`.
- 업로드 진행률 스트림: `ProgressInputStream`.
- Redis 보조: `RedisSupport`, `RedisConfig`.
- 게시글 배치 export DTO: `PostExportDto`.

## 비기능 요구사항

- 검색/집계 조회는 로우 쿼리(Native SQL)로 작성하고 N+1을 해결한다(목록과 연관 데이터는 조인 또는 배치 IN 조회).
- 예외는 도메인 advice(`AuthControllerAdvice`)와 `ErrorCode`/커스텀 예외로 일관 처리한다.
