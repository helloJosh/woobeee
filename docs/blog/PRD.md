# PRD — blog (기술 블로그)

`blog` 도메인은 게시글, 카테고리, 댓글, 좋아요 기능을 담당한다.

- 베이스 경로: `/api/back/posts`, `/api/back/categories`, `/api/back/comments`, `/api/back/likes`
- 코드: `com.woobeee.mvc.blog`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 사용자가 게시글을 카테고리·검색어·페이징으로 조회하고, 상세를 다국어(locale)로 본다.
- 로그인 사용자가 댓글(대댓글 포함)과 좋아요로 상호작용한다.

## 모델

- `Posts`(제목/본문 한국어·영어 필드 `titleKo/textKo`, `titleEn/textEn`, `categoryId`, `createdAt`), `Categories`, `Comments`(대댓글 구조), `Likes`.
- 저장소: 단순 CRUD는 Spring Data JPA(`PostRepository`, `CategoryRepository`, `CommentRepository`, `LikeRepository`), 검색/집계는 로우 쿼리(Native SQL)로 작성한다. 기존 `PostQueryRepositoryImpl`의 QueryDSL 구현은 로우 쿼리로 마이그레이션 대상.
- 서비스는 interface/impl 분리(`PostService`/`PostServiceImpl` 등).

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 |
| --- | --- |
| 게시글 목록 조회 | `GET /api/back/posts` |
| 게시글 상세 조회 | `GET /api/back/posts/{postId}` |
| 게시글 등록(멀티파트) | `POST /api/back/posts` |
| 게시글 삭제 | `DELETE /api/back/posts/{postId}` |
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

- **목록/검색**: 카테고리 id 집합·검색어 `q`·`locale`·페이징으로 조회한다. 정렬은 `createdAt desc, id desc`. locale이 `en`이면 영어 제목/본문, 아니면 한국어 제목/본문을 대상으로 부분일치 검색한다(`PostQueryRepositoryImpl.searchPosts`). 상세: [`adr/ADR-001-postpaging.md`](adr/ADR-001-postpaging.md).
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

> ⚠️ 현재 `src/test`에 **blog 도메인 테스트가 없다.** 아래 인수 기준은 우선 작성 대상이다(커버리지 갭).

| ID | 인수 기준 (Given–When–Then) | 커버 테스트 |
| --- | --- | --- |
| BLOG-AC-01 | 게시글 목록은 `createdAt DESC, id DESC`로 정렬해 반환한다 | 미작성 — 추가 필요 |
| BLOG-AC-02 | `locale=en`이면 영어 컬럼(`title_en/text_en`), 그 외에는 한국어 컬럼을 대상으로 검색한다 | 미작성 — 추가 필요 |
| BLOG-AC-03 | 카테고리 id 집합이 주어지면 해당 카테고리 게시글만 조회한다(`IN`) | 미작성 — 추가 필요 |
| BLOG-AC-04 | 카테고리별 게시글 수를 `GROUP BY`로 집계해 반환한다 | 미작성 — 추가 필요 |
| BLOG-AC-05 | 댓글은 댓글/대댓글(parent) 구조로 조회·생성된다 | 미작성 — 추가 필요 |
| BLOG-AC-06 | 좋아요는 로그인 사용자 기준으로 등록/취소(토글)된다 | 미작성 — 추가 필요 |
| BLOG-AC-07 | 클라이언트가 보낸 `loginId` 헤더는 무시된다 — 신원은 오직 유효한 access token 에서만 파생된다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-08 | **유효한** `ROLE_MEMBER` 토큰으로 게시글/카테고리 쓰기(POST/PUT/DELETE)를 호출하면 `403` + `ApiResponse` 실패 봉투를 반환한다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-09 | `ROLE_ADMIN` 토큰은 게시글/카테고리 쓰기를 통과한다. 읽기(GET)는 누구나 가능하다 | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-10 | 댓글/좋아요 쓰기는 로그인 회원(`ROLE_MEMBER`)이면 가능하다(ADMIN 불필요) | `AccessTokenLoginIdHeaderFilterTest` |
| BLOG-AC-11 | `PUT /api/back/posts/{postId}` 는 작성자 본인일 때 제목·본문·카테고리를 갱신하고, 마크다운 파트가 없으면 본문을 보존한다 | `PostServiceImplTest` |
| BLOG-AC-12 | 무토큰 또는 만료(스토어에 없는) 토큰으로 게시글/카테고리 쓰기를 호출하면 `401` + `ApiResponse` 실패 봉투를 반환한다 — 권한 부족(403)과 구분해야 프론트가 refresh 후 재시도할 수 있다 | `AccessTokenLoginIdHeaderFilterTest` |

## 지원 기능

- 페이징 보조: `CustomPageable`.
- 업로드 진행률 스트림: `ProgressInputStream`.
- Redis 보조: `RedisSupport`, `RedisConfig`.
- 게시글 배치 export DTO: `PostExportDto`.

## 비기능 요구사항

- 검색/집계 조회는 로우 쿼리(Native SQL)로 작성하고 N+1을 해결한다(목록과 연관 데이터는 조인 또는 배치 IN 조회).
- 예외는 도메인 advice(`AuthControllerAdvice`)와 `ErrorCode`/커스텀 예외로 일관 처리한다.
