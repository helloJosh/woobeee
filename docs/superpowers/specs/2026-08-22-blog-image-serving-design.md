# 블로그 이미지 서빙 — AS-IS / TO-BE / 변경 지점

- 날짜: 2026-08-22
- 상태: **해결 — 공개 URL 방식을 버리고 앱 경유 스트리밍으로 전환했다. 인프라 작업 0건.** 아래 §5 참조
  (§2 의 공개 URL 계획과 §4 의 1차 구현은 기록으로 남긴다. §2-2 68행의 `S3_PUBLIC_BASE_URL=https://woobeee.com`
  은 **틀린 값이었다** — apex 는 오리진이 없다. 그 줄이 §4 를 쓸 때 고쳐지지 않아 2차 시도까지 새게 만들었다.)
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

---

## 4. 구현 기록 (2026-08-22)

### 4-1. 계획과 달랐던 점 둘

**① 522 의 원인은 프록시 규칙 부재가 아니라 apex 오리진 자체가 죽은 것이다.**
진단을 apex(`woobeee.com`)로 했는데, 그 호스트는 `/` 조차 522 를 낸다. 공개 도메인은
`www` 쪽이고 거기서 같은 경로는 522 가 아니라 **404** 다(= Next 가 받고 라우트가 없음).
따라서 프록시 규칙만 넣고 base 를 apex 로 두면 여전히 안 뜬다 — base 는 `www` 여야 한다.

| 호스트 | `/` | `/woobeee/13/ctid_structure.png` |
| --- | --- | --- |
| `woobeee.com` (apex) | 522 | 522 |
| `www.woobeee.com` | 200 | 404 |

**② 프록시는 nginx 가 아니라 Next rewrites 로 넣었다.**
이 호스트의 리버스 프록시는 nginx 가 아니라 `cloudflared tunnel run --token-file …` 이다.
토큰 방식이라 ingress 규칙이 Cloudflare 대시보드에 있어 레포에서 못 고친다. 반면 터널은
`www` 를 `:3000`(Next)으로 넘기고 있으므로, Next 의 `rewrites()` 에 버킷 경로를 얹으면
대시보드를 건드리지 않고 같은 결과가 된다. 설정이 레포 안에 남는다는 장점도 있다.

### 4-2. 반영한 것

- `StorageProperties.publicBaseUrl` 추가, `application.yaml` 에
  `public-base-url: ${S3_PUBLIC_BASE_URL:http://localhost:9000}`.
- `PostServiceImpl.publicUrl()` 이 그 값을 쓴다. 하드코딩 제거. base 끝 슬래시 정리 추가.
- `front/next.config.mjs` 에 `/{bucket}/:path*` → `S3_ORIGIN`(기본 `http://localhost:9000`) rewrite.
- `docs/blog/PRD.md` 에 BLOG-AC-13 / BLOG-AC-14 추가, `PostServiceImplTest` 에 테스트 3개.

### 4-3. 남은 것 — 버킷 익명 읽기 (미적용)

MinIO 는 지금 익명 GET 에 403 을 낸다. 오브젝트는 있다(`13/ctid_structure.png` 확인).
정책을 걸기 전까지는 프록시가 뚫려도 이미지가 403 이다.

계획서는 `mc anonymous set download local/woobeee` 를 제안했는데, 그러면 같은 버킷의
`profiles/` 도 함께 공개된다(계획서 §2-1 이 이미 지적한 점). 지금 `profiles/` 오브젝트는
0개라 당장 새는 것은 없지만, 프로필 업로드가 시작되면 공개된다. 그래서 Deny 를 함께 건
아래 정책을 권한다:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "PublicReadBlogImages", "Effect": "Allow", "Principal": {"AWS": ["*"]},
      "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::woobeee/*"] },
    { "Sid": "KeepProfilesPrivate", "Effect": "Deny", "Principal": {"AWS": ["*"]},
      "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::woobeee/profiles/*"] }
  ]
}
```

적용 후 확인할 것 — 익명 GET 이 blog 이미지는 200, `profiles/` 는 403 이어야 하고,
**서명된**(access key) `profiles/` 읽기는 계속 200 이어야 한다(MinIO root 는 버킷 정책을
우회하지만, 실제로 확인하고 넘어가야 한다).

버킷을 공개하고 싶지 않다면 대안이 있다: app-mvc 가 자격증명으로 오브젝트를 스트리밍하는
읽기 엔드포인트(`GET /api/back/posts/{id}/images/{파일명}`)를 두고 본문 URL 을 그쪽으로
돌리는 것. 버킷은 계속 비공개로 두고 기존 `/api/back/*` rewrite 를 그대로 타지만, 코드가
늘고 이미지 트래픽이 앱을 거친다.

---

## 5. 2차 전환 — 앱 경유 스트리밍 (2026-08-22)

### 5-1. 왜 1차 구현으로도 안 떴나

§4 의 구현(설정으로 뺀 공개 URL + Next rewrite)을 배포한 뒤에도 이미지는 여전히 안 나왔다.
프로덕션 실측으로 층을 나눠 보니 **두 군데가 동시에 막혀 있었다**:

| 층 | 상태 | 원인 |
| --- | --- | --- |
| 본문에 박힌 URL | `https://woobeee.com/woobeee/13/…` (apex) | §2-2 68행이 apex 를 지시했고 §4 에서 고쳐지지 않았다. 그 값을 주입했거나 app-mvc 를 재기동하지 않아 옛 jar 가 돌았다 — 출력이 같아 원격에서는 구분 불가 |
| MinIO 서빙 | `403 AccessDenied` | §4-3 의 버킷 정책이 미적용. rewrite 는 배포돼 404→403 으로 바뀌었다(= 프록시는 뚫렸고 MinIO 가 거부) |

즉 **URL 을 www 로 고쳐도 403 이라 안 보인다.** 한쪽만 고치면 증상이 그대로여서, 두 라운드
연속으로 "고쳤는데 계속 안 됨"이 됐다.

### 5-2. 진짜 문제는 인프라 의존이었다

공개 URL 방식은 고칠 지점이 **레포 밖에 둘**(env 주입 + 재기동, 버킷 정책) 있었다. 두 번의
시도가 실패한 이유가 코드가 아니라 그 둘이 적용되지 않은 것이었으므로, 같은 방식으로 3차를
시도하는 것은 같은 함정을 다시 밟는 것이다.

반면 `/api/back/*` 는 이미 프로덕션에서 정상 동작한다(진단 중 `/api/back/posts/13` 이 200).
그 경로로 이미지를 흘리면 **인프라 작업이 0건**이 된다 — §4-3 이 이미 대안으로 적어 둔 방식이다.

### 5-3. 반영한 것

- `PostServiceImpl.publicUrl()` → `/api/back/posts/{postId}/images/{파일명}` **상대 경로**.
  호스트를 아예 만들지 않으므로 로컬·프로덕션이 같은 값으로 동작한다. 파일명은 퍼센트
  인코딩한다(업로드가 원본 basename 을 키로 쓰므로 한글이 들어올 수 있다. `URLEncoder` 가
  공백을 `+` 로 만드는데 경로에서는 리터럴 `+` 라 `%20` 으로 바꾼다).
- `PostService.loadPostImage` / `PostController.getPostImage` 추가 — 앱이 자격증명으로
  `getObjectAsBytes` 해서 바이트와 저장된 contentType 을 반환한다. `ApiResponse` 봉투를
  태우지 않고(`<img>` 가 여는 주소다), 1년 immutable 캐시 헤더를 붙인다. 쓰기만 ADMIN 이므로
  이 GET 은 공개다.
- 파일명은 basename 만 남긴다. 그대로 이어 붙이면 `13/../profiles/1/x.png` 로 같은 버킷의
  비공개 프로필 이미지를 인증 없이 읽을 수 있었다.
- `getAllPost` 에도 치환을 적용했다. 치환이 `getPost` 에만 걸려 있어 목록 응답에는
  `${파일명}` 원문이 그대로 내려가고 있었다(별개 결함).
- 되돌린 것: `StorageProperties.publicBaseUrl`, `application.yaml` 의 `public-base-url`,
  `next.config.mjs` 의 `/{bucket}/:path*` rewrite. 마지막 것은 죽은 설정일 뿐 아니라 공개
  도메인에서 MinIO 의 버킷 경로를 그대로 노출했다(정책이 느슨해지는 순간 버킷이 열린다).

### 5-4. 이 방식으로 사라진 과제

- 버킷 익명 읽기 정책(§4-3) — **불필요**. 버킷은 계속 비공개고 `profiles/` 노출 위험도 없다.
- `S3_PUBLIC_BASE_URL` 주입과 apex/www 구분 — **불필요**. 설정 자체가 없다.
- 리버스 프록시 규칙 추가(§2-1) — **불필요**. 기존 `/api/back/*` 를 탄다.

대가는 이미지 트래픽이 앱을 거치는 것이다. 개인 블로그 규모에서는 문제되지 않고, 필요해지면
1년 immutable 캐시 헤더 위에 CDN 을 얹는 것이 다음 수다.

### 5-5. 남은 것

- **app-mvc 재기동이 필요하다.** 이 변경은 서버 코드이므로 배포·재기동 전까지 프로덕션 본문은
  옛 URL 을 계속 내려보낸다. 확인: 응답에 `/api/back/posts/…/images/…` 가 보이고 그 경로가
  `200 image/png` 를 반환하면 완료.
- `generatePresignedUrl` 과 `s3Presigner` 주입은 blog 쪽에서 호출부가 없다(프로필 이미지는
  계속 presign 을 쓴다). 되돌릴 계획이 없다면 blog 에서는 삭제 대상.

---

## 5. 최종 결론 (2026-08-25) — presigned URL

§4 의 앱 스트리밍을 거쳐 **presigned URL** 로 확정했다. Cloudflare 터널에 MinIO 전용
서브도메인이 추가되면서 전제가 바뀌었다.

```
image.woobeee.com  →  http://localhost:9000   (Published application, order 2)
www.woobeee.com    →  http://localhost:3000   (order 1)
```

### 5-1. 이 방식이 이긴 이유

**버킷을 공개하지 않아도 된다.** 서명이 접근을 허가하므로 익명 읽기 정책이 필요 없다 — §4-3
에서 고민했던 `profiles/` 동반 공개 문제가 아예 사라진다. 실측으로 확인했다:

```
GET https://image.woobeee.com/woobeee/13/ctid_structure.png            → 403 AccessDenied  (서명 없음)
GET https://image.woobeee.com/woobeee/13/ctid_structure.png?X-Amz-…    → 200 image/png 152632B
```

**앱이 이미지 트래픽을 지지 않는다.** 앱 스트리밍은 `getObjectAsBytes` 로 오브젝트를 힙에
통째로 올렸다. 첨부 상한이 500MB 인데 읽기는 공개·무인증이라, 큰 첨부 하나로 OOM 이 가능한
구조였다. presign 은 바이트가 MinIO 에서 브라우저로 바로 간다.

### 5-2. 핵심 — endpoint 를 둘로 쪼갰다

`S3Presigner` 가 **서버용 endpoint** 로 서명하던 것이 CLAUDE.md "MinIO CORS 미검증" 항목의
실제 내용이었다. 서명은 host 를 포함하므로 만든 뒤 문자열로 못 고친다.

| 설정 | 쓰는 곳 | 값 |
| --- | --- | --- |
| `storage.s3.endpoint` (`S3_ENDPOINT`) | `S3Client` — 서버→MinIO (putObject 등) | `http://localhost:9000` |
| `storage.s3.public-endpoint` (`S3_PUBLIC_ENDPOINT`) | `S3Presigner` — 브라우저가 열 URL | `https://image.woobeee.com` |

`public-endpoint` 가 비면 `endpoint` 로 폴백하므로 로컬은 설정 없이 그대로 돈다.

### 5-3. 남은 것

- ~~`GET /api/back/posts/{id}/images/{파일명}`~~ **제거했다** (2026-08-25). 본문 URL 이
  presigned 절대 URL 이 되어 호출부가 없어졌다. `PostService.loadPostImage`, `PostImage`
  레코드, 관련 테스트 3개, 그리고 그로 인해 쓰이지 않게 된 import 14개를 함께 지웠다.
  `docs/api/README.md` 의 해당 행도 삭제했다.
- **브라우저 캐시가 안 걸린다.** 서명에 `X-Amz-Date` 가 들어가 URL 이 조회마다 바뀐다. 앱
  스트리밍 때 붙였던 `immutable` 1년 캐시의 이점이 사라졌다 — 같은 이미지를 매번 다시
  받는다. 유효기간을 1일(`presigned-url-expiration-seconds: 86400`)로 늘린 것은 만료로
  깨지는 것을 막을 뿐 캐시 문제는 그대로다. 만료 시각을 시간 단위로 내림해 URL 을 결정적으로
  만들면 캐시가 살아난다.
- 첨부 상한 500MB 와 SVG contentType 허용목록은 presign 과 무관하게 여전히 과제다
  (SVG 는 이제 `image.woobeee.com` 오리진에서 서빙되므로 XSS 영향 범위는 줄었다).
- 기보(replay) 뷰어도 같은 `public-endpoint` 분리가 필요하다 — app-webflux 쪽은 아직
  서버용 endpoint 로 presign 한다.

---

## 6. 결정적 presigned URL (2026-08-26)

§5 의 presign 에는 구멍이 있었다 — **캐시가 전혀 걸리지 않았다.**

### 6-1. 실측한 문제

CDN 은 쿼리스트링까지 캐시 키에 넣는다. presigned URL 은 `X-Amz-Date` 와 `X-Amz-Signature`
가 생성마다 달라지므로 방문자마다 다른 오브젝트가 된다.

```
같은 서명 URL 3번:   MISS → MISS → HIT     ← URL 이 같으면 결국 캐시된다
새 서명 URL 2개:      MISS,  MISS           ← 방문자마다 원점까지 내려온다
```

글 13 은 이미지 2장 약 300KB 다. 100명이 오면 원점 트래픽이 300KB 대 **30MB** 로 갈린다.
게다가 같은 사람이 새로고침만 해도 서명이 새로 생겨 또 받는다. MinIO 가 앱과 같은 호스트·같은
터널 뒤에 있으므로 "presign 이 바이트를 넘겨 준다"는 이점이 업링크에는 해당되지 않는다 —
실제로 아끼는 것은 JVM 힙뿐이다.

### 6-2. AWS SDK 로는 불가능하다

서명 시각을 우리가 정해야 하는데 SDK 에 수단이 없다.

```
S3Presigner.Builder            → credentialsProvider, region, endpointOverride,
                                  serviceConfiguration, dualstack, fips … 클록 없음
GetObjectPresignRequest.Builder → getObjectRequest, signatureDuration … 서명 시각 없음
```

그래서 `PresignedUrlFactory` 에 SigV4 쿼리 서명을 직접 구현했다. 클록을 주입받으므로 테스트가
시각을 고정할 수 있다.

### 6-3. 버킷 1시간 / TTL 24시간

버킷(내림 단위)과 TTL 은 서로 다른 일을 한다 — 버킷은 **캐시 공유 범위**를, TTL 은 **안전
여유**를 정한다. 둘을 같게 두면 시간 끝에 들어온 방문자가 곧 만료되는 URL 을 받아 이미지가
깨진다(만료 절벽).

| | 버킷 = TTL = 1h | 버킷 1h / TTL 24h |
| --- | --- | --- |
| 10:00:01 방문자 | 남은 유효기간 ~1h | ~24h |
| 10:59:59 방문자 | **남은 유효기간 1초** | **23h** |

TTL ≤ 버킷이면 `PresignedUrlFactory` 가 `IllegalStateException` 을 던진다 — 조용히 깨지는
것보다 기동에서 터지는 편이 낫다.

### 6-4. 검증

Java 팩토리가 만든 URL 을 실제 MinIO 에 쳤다.

```
X-Amz-Date=20260825T160000Z      ← 정시로 내려갔다
HTTP 200  image/png  152632 bytes
HTTP 200  image/png  147973 bytes

3초 간격으로 두 번 생성 → 두 URL 이 완전히 동일
같은 URL 4번 → MISS → HIT → HIT → HIT
```

`PresignedUrlFactoryTest` 11개가 결정성·절벽 없음·host 출처·인코딩을 고정한다. 서명이 유효한지
(MinIO 가 받아주는지)는 실 스토리지가 필요해 단위 테스트로는 못 본다 — 위 수동 검증이 그것을
대신한다.

### 6-5. 프로필 이미지도 같은 방식으로

`GET /api/auth/members/{memberId}/profile-image` 스트리밍을 제거하고 presigned URL 로 옮겼다.
아바타는 헤더에 있어 모든 페이지에 뜨고 댓글에 붙으면 한 화면에 수십 개라 캐시가 특히 중요한데,
결정적 URL 이 되면서 그 조건이 갖춰졌다. 바이트도 앱을 거치지 않는다.

대가: 브라우저가 오브젝트 키를 보게 되고(`profiles/{memberId}/{uuid}/{파일명}`), URL 이 시간당
한 번 바뀌어 아바타를 1시간에 한 번 다시 받는다. 둘 다 감수할 만하다.

### 6-6. 남은 것

- app-webflux 의 기보 뷰어는 아직 SDK presigner 를 **서버용 endpoint** 로 쓴다. `PresignedUrlFactory`
  를 core 로 올리거나 같은 모양을 옮겨야 한다.
- 글 첨부 상한 500MB 와 contentType 허용목록 부재는 그대로 남았다(프로필은 5MB + 허용목록 있음).
- presign 은 글 존재를 확인하지 않는다 — 지운 글의 첨부도 서명만 있으면 열린다.
