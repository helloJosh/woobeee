# 블로그 이미지 서빙 — AS-IS / TO-BE / 변경 지점

- 날짜: 2026-08-22
- 상태: 검토 중 (TO-BE 는 계획이며 코드에 반영되지 않음)
- 관련 문서: [블로그 ADMIN 편집기 설계](2026-08-06-blog-admin-editor-design.md), `docs/blog/PRD.md`, `docs/api/README.md`

## 0. 요약

| | AS-IS (현재 사실) | TO-BE (계획) |
| --- | --- | --- |
| 업로드 | `putObject` 가 서버 측 `S3_ENDPOINT` 의 MinIO 로 저장 — **동작함** | 그대로 |
| 조회 URL 생성 | `PostServiceImpl.publicUrl()` 이 `https://woobeee.com` 을 **하드코딩**해 `{도메인}/{버킷}/{postId}/{파일명}` 조립 | base 를 `S3_PUBLIC_BASE_URL` 설정값으로 — 로컬/프로덕션이 같은 코드 |
| 실제 서빙 | 프로덕션에 `/woobeee/*` → MinIO 라우팅이 **없음** → Cloudflare **522** | 리버스 프록시가 `/woobeee/*` 를 MinIO :9000 으로 전달 |
| 접근 제어 | URL 에 서명 없음(공개 URL 방식), presign 코드는 주석 처리 | 버킷 **익명 읽기(download)** 허용 — 쓰기는 access key 유지 |

## 1. AS-IS (사실 — 2026-08-21 진단 기준)

- 글 저장(multipart)은 성공한다. 이미지 파트는 서버가 `{postId}/{파일명}` 키로
  `putObject` 한다 (`PostServiceImpl.uploadImages`). 저장이 성공했으므로 업로드 자체는
  서버 측 `S3_ENDPOINT` 가 가리키는 MinIO 에 들어간 것으로 판단한다.
- 조회 시 `replaceImagePlaceholdersWithPresignedUrls` 가 본문의 `${파일명}` 을
  `publicUrl(postId, fileName)` 로 치환한다. 이 함수가 `https://woobeee.com` 을
  하드코딩한다 (`PostServiceImpl.java:258` 부근). presigned GET(`generatePresignedUrl`,
  7일 서명)은 구현돼 있으나 호출부가 주석 처리돼 있다.
- 실측: 글 id 13 의 본문은 `https://woobeee.com/woobeee/13/ctid_structure.png` 형태로
  내려오고, 그 URL 은 **HTTP 522** (Cloudflare 가 오리진 연결 실패) 를 반환한다. 즉
  도메인의 `/woobeee/*` 경로를 받아줄 리버스 프록시 규칙이 프로덕션에 없다.
- 저장 원문(DB)에는 `${파일명}` 플레이스홀더가 그대로 있다 — URL 은 조회 시점에만
  조립되므로, **서빙 경로가 열리면 기존 글은 수정 없이 바로 뜬다.**

## 2. TO-BE

### 2-1. 인프라 (선행 — 코드보다 먼저, 이것만으로 현재 증상 해소)

Mac mini(프로덕션 호스트)에서:

1. **리버스 프록시 라우팅** — woobeee.com 을 서빙하는 프록시(nginx/캐디/터널)에
   버킷 경로를 MinIO 로 넘기는 규칙 추가. nginx 예:

   ```nginx
   location /woobeee/ {
       proxy_pass http://localhost:9000/woobeee/;
       # 이미지 응답이므로 버퍼/캐시 기본값으로 충분. 인증 헤더 전달 불필요.
   }
   ```

2. **버킷 익명 읽기** — 공개 URL 에는 서명이 없으므로 읽기만 익명 허용:

   ```bash
   mc anonymous set download local/woobeee
   ```

   쓰기는 여전히 access key 필요. 블로그 이미지는 공개 게시물의 일부이므로 읽기
   공개는 의도된 정책이다. (프로필 이미지 등 비공개 오브젝트를 같은 버킷에 두면
   함께 공개되므로, 필요해지면 버킷을 분리한다 — 현재 `profiles/` prefix 가 같은
   버킷에 있다는 점을 인지할 것.)

3. **확인** — `curl -I https://woobeee.com/woobeee/13/ctid_structure.png` 가
   `200 image/png` 를 반환하면 완료. 글 13 이 즉시 정상 표시된다.

### 2-2. 코드 (후속 — 하드코딩 제거)

- `publicUrl()` 의 base 를 설정으로 뺀다: `storage.s3.public-base-url`
  (환경변수 `S3_PUBLIC_BASE_URL`, 기본값 `http://localhost:9000` — 로컬에서 프록시
  없이 MinIO 직결로 동작).
- `application.yaml` 의 `woobeee.storage.s3` 블록에 키 추가, `StorageProperties` 에
  필드 추가, `PostServiceImpl.publicUrl()` 이 그 값을 쓴다.
- 프로덕션은 `S3_PUBLIC_BASE_URL=https://woobeee.com` 을 주입한다.
- 죽은 코드 정리: 주석 처리된 `replaceLocalhostToDev`, 호출부 없는
  `generatePresignedUrl` 은 presign 방식으로 되돌릴 계획이 없다면 삭제한다.
  (되돌리려면 브라우저가 여는 presign URL 의 host 도 공개 도메인이어야 하므로
  어차피 같은 프록시가 필요하다 — 익명 읽기 대신 서명을 원할 때의 대안으로만 남긴다.)

### 2-3. 검증 (AC 후보 — 구현 시 `docs/blog/PRD.md` 에 편입)

| ID(안) | 인수 기준 | 비고 |
| --- | --- | --- |
| BLOG-AC-13 | 조회 응답의 이미지 URL 은 `S3_PUBLIC_BASE_URL` 설정값을 base 로 조립된다 — 하드코딩 없음 | `PostServiceImplTest` 에 단위 테스트 |
| BLOG-AC-14 | `${파일명}` 플레이스홀더는 저장 원문에 유지되고 조회 시에만 치환된다 (기존 동작 고정) | 회귀 방지 |

## 3. 범위 밖

- MinIO 미기동/버킷 부재 시 저장이 어떤 오류 봉투로 나가는지(catch-all advice 부재
  문제)는 별도 과제 — CLAUDE.md "app-mvc 에 catch-all advice 없음" 항목.
- 발급받고 등록하지 않은 업로드의 lifecycle 정리(고아 오브젝트)는 기존 후속 과제 유지.
- CDN/캐시 정책, 이미지 리사이징.
