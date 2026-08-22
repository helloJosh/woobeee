# 프로필 이미지 업로드·표시 — 설계

- 날짜: 2026-08-22
- 상태: 승인 (구현 대상)
- 관련 문서: [블로그 이미지 서빙](2026-08-22-blog-image-serving-design.md), `docs/auth/PRD.md`, `docs/api/README.md`

## 0. 왜 하는가

마이페이지는 프로필 이미지를 **표시만** 한다. 업로드 화면이 없다(CLAUDE.md 후속 과제).
헤더에도 아바타가 없다. 사용자 요청은 셋이다 — 마이페이지에서 업로드·수정, 이미지 표시,
헤더의 로그인/로그아웃 옆에 아바타(기본은 회색).

그런데 구현에 들어가기 전에 확인된 사실이 하나 더 있다. **현재 프로필 이미지는 프로덕션에서
깨져 있다.** 블로그 이미지가 깨졌던 것과 같은 원인이다:

```
GET /api/auth/me →
  "profileImageUrl": "http://localhost:9000/woobeee/profiles/1/…?X-Amz-Signature=…"
                      ^^^^^^^^^^^^^^^^^^^^^ S3_ENDPOINT 에서 나온 호스트
```

`MemberProfileImageService.createPresignedDownloadUrl` 이 presigned GET URL 을 만드는데,
그 호스트는 **서버가 MinIO 에 붙는 주소**다. 브라우저가 열 주소가 아니다. 업로드용 presigned
PUT 도 같은 문제를 갖는다 — 즉 기존 presigned 흐름은 프로덕션에서 업로드도 표시도 안 된다.
게다가 `X-Amz-Expires=600` 이라 10분 뒤 만료된다.

그래서 이 작업은 "기능 추가"가 아니라 **서빙 방식 교체 + 화면 추가**다.

## 1. 결정 사항 (사용자 확인)

| 항목 | 결정 | 근거 |
| --- | --- | --- |
| 업로드·서빙 방식 | **앱 경유** (multipart 업로드 + 앱 스트리밍) | 블로그 이미지와 동일. 인프라 작업 0건, 만료 없음. presigned 는 프로덕션에서 못 쓴다 |
| 조회 노출 범위 | **인증 유지 + blob URL** | 노출 범위를 지금과 같게 유지한다. 프론트가 `fetch` + `Authorization` 으로 받아 `URL.createObjectURL` 로 `<img>` 에 넣는다 |
| 헤더 모양 | **로그아웃 버튼 옆 아바타**, 클릭 → `/mypage` | 요청 그대로. 도달 불가인 `UserMenu` 드롭다운은 삭제 |
| 마이페이지 UI | 아바타 **호버 시 "변경" 오버레이** + 클릭 파일 선택 + 드래그앤드롭, 이미지 있을 때만 "삭제" | 화면이 길어지지 않는다 |
| 업로드 제한 | png/jpeg/webp/gif, **5MB** | 업로드가 앱을 거치므로 상한이 없으면 큰 파일이 앱 힙을 받는다 |

### 왜 `<img src="/api/auth/me/profile-image">` 가 안 되는가

토큰은 `localStorage` 에 있고(쿠키 아님), `<img>` 태그는 `Authorization` 헤더를 붙일 수 없다.
그래서 인증을 유지하는 선택에서는 blob URL 이 유일한 길이다. `markdownUrlTransform` 이 이미
`blob:` 을 통과시키므로(블로그 편집기 미리보기용으로 열어 둔 것) 새니타이저 변경은 없다.

대가는 문서화해 둔다: 전역 새로고침마다 한 번 재요청하고(통짜 캐싱이 안 된다), blob URL
정리가 필요하고, 나중에 게임방·댓글에서 **다른 사람** 아바타가 필요해지면 이 방식은 다시
설계해야 한다(그때는 memberId 공개 엔드포인트가 후보다).

## 2. 백엔드 (app-mvc, auth 도메인)

### 2-1. 엔드포인트

| Method | Path | 인증 | 내용 |
| --- | --- | --- | --- |
| `POST` | `/api/auth/me/profile-image` | 본인 | multipart `file` → 교체. 타입·크기 검증, `profiles/{memberId}/{uuid}/{파일명}` 저장, 이전 오브젝트 삭제. `ApiResponse<MemberProfileImageResponse>` |
| `GET` | `/api/auth/me/profile-image` | 본인 | 바이트 스트리밍. `ApiResponse` 봉투 없음 — `Content-Type` 은 저장된 값, 미설정이면 404 |
| `DELETE` | `/api/auth/me/profile-image` | 본인 | 기존 그대로 204 |

**삭제**: `POST /api/auth/me/profile-image/presigned-url`, `PUT /api/auth/me/profile-image`(등록),
`createPresignedDownloadUrl`. 셋 다 presigned 흐름이고 프론트 호출부가 없으며 프로덕션에서
동작하지 않는다. 관련 DTO(`MemberProfileImagePresignedUrlRequest`,
`MemberProfileImageRegisterRequest`, `MemberProfileImageUploadUrlResponse`)도 함께 정리한다.

### 2-2. 응답 계약 변경

`MemberProfileResponse.profileImageUrl: String|null` → **`hasProfileImage: boolean`**.

blob 방식에서 프론트는 URL 이 아니라 "이미지가 있는지"만 알면 된다. 못 쓰는 URL 을 내려보낸
것이 이 버그의 원인이었으므로, 계약에서 아예 없앤다.

### 2-3. 검증과 저장

- 타입: 기존 `ALLOWED_CONTENT_TYPES`(png/jpeg/webp/gif) 재사용. 밖이면 400.
- 크기: `MAX_PROFILE_IMAGE_BYTES = 5 * 1024 * 1024`. 초과면 400.
  (`application.yaml` 의 multipart 제한은 이미 500MB 이므로 이 상한은 애플리케이션 규칙이다.)
- 키: 기존 스킴과 `sanitizeFileName` 을 그대로 쓴다 — `profiles/{memberId}/{uuid}/{파일명}`.
  memberId 는 요청 본문이 아니라 **토큰에서 온 회원**으로 정한다(기존 규칙 유지).
- 교체 시 이전 오브젝트 삭제는 기존 `register` 의 동작을 새 업로드 메서드로 옮긴다.
- 트랜잭션은 열지 않는다(기존 주석의 근거 유지: 저장 커밋 뒤 삭제해야 삭제 실패가 프로필을
  깨뜨리지 않고 고아 오브젝트만 남는다).

### 2-4. 스트리밍

블로그 이미지 엔드포인트와 같은 모양이다 — `getObjectAsBytes` 로 읽어 바이트와 저장된
`contentType` 을 반환한다. 캐시 헤더는 `private` 로 둔다(본인 전용 리소스이므로 `public` 이면
공유 캐시에 남는다). 프론트가 blob 으로 받으므로 긴 `max-age` 는 의미가 없다 — `no-store` 로
두고 재요청 시점을 프론트가 정한다.

## 3. 프론트

### 3-1. `lib/profile-image.ts` (신규, React-free)

판단 로직은 컴포넌트가 아니라 여기 둔다(CLAUDE.md: `lib/` 밖으로 나간 판단은 검증 밖으로
나간다. 세 차례 뮤테이션 스윕에서 끝까지 죽지 않은 변이는 전부 컴포넌트 층이었다).

- `validateProfileImageFile(file)` → `{ok: true} | {ok: false, reason: string}`
  타입 화이트리스트 + 5MB. 서버와 같은 값을 쓰되 프론트에서 먼저 걸러 왕복을 줄인다.
- `pickImageFile(files)` → 드롭된 것 중 첫 이미지 파일 또는 `null`
- `describeProfileImageError(cause)` → 사용자 문구

### 3-2. `AuthProvider`

blob URL 을 한 번 받아 컨텍스트에 들고 헤더와 마이페이지가 공유한다.

- 인증 상태이고 `hasProfileImage` 면 `fetch` + `Authorization` → `blob()` → `createObjectURL`
- 교체·삭제 후 `refreshProfileImage()` 로 다시 받고, **이전 blob 은 `revokeObjectURL`**
- 언마운트 시에도 정리한다

`profileImageUrl: string | null` 과 `refreshProfileImage: () => Promise<void>` 를 컨텍스트에
추가한다.

### 3-3. 헤더

로그아웃 버튼 **왼쪽**에 원형 아바타. 클릭 → `/mypage`. 기본은 회색 원 +
`UserRound` 아이콘(마이페이지와 같은 모양). 비로그인 상태에는 아바타 없이 "로그인"만.

도달 불가인 `components/auth/user-menu.tsx` 를 삭제한다 — `useAuth` 가 `user` 를 채우지
않으므로 이 컴포넌트는 렌더된 적이 없다. 안 쓰는 `User.profileImage` 필드도 함께 정리한다.

### 3-4. 마이페이지

기존 프로필 카드의 아바타를 업로드 표면으로 바꾼다.

- 호버 시 "변경" 오버레이, 클릭 → 숨은 `<input type="file">`
- 같은 아바타 영역에 드래그앤드롭도 받는다(블로그 편집기와 일관)
- 이미지가 있을 때만 옆에 "삭제"
- 업로드 중 진행 표시, 실패 시 그 자리에 인라인 오류
- 성공하면 `refreshProfileImage()` — 헤더 아바타도 함께 갱신된다

## 4. 테스트

| 대상 | 내용 |
| --- | --- |
| `MemberProfileImageServiceTest` | 허용 목록 밖 타입 400, 5MB 초과 400, 키 스킴이 토큰의 memberId 를 쓴다, 교체 시 이전 오브젝트 삭제, 스트리밍이 바이트+contentType 반환, 미설정이면 404 |
| `MemberProfileImageControllerTest` | 세 엔드포인트 배선, 스트리밍이 `ApiResponse` 봉투를 타지 않는다 |
| `lib/profile-image.test.ts` | 타입·크기 검증 경계, 드롭 파일 선택, 오류 문구 |

`docs/auth/PRD.md` 갱신:
- **폐기**: AUTH-AC-10, 11, 12 (presigned 발급·prefix 검증 전제)
- **재작성**: AUTH-AC-13(교체 시 이전 오브젝트 삭제 — 새 업로드 경로 기준),
  AUTH-AC-14(`GET /me` 는 `hasProfileImage` 를 반환)
- **신규**: 업로드 타입·크기 검증, 스트리밍 계약, 미설정 404

`docs/api/README.md` 의 auth 절도 함께 갱신한다(엔드포인트 3개 추가·2개 삭제).

## 5. 범위 밖

- 다른 회원의 아바타 표시(게임방 참가자 목록, 댓글 작성자). 필요해지면 §1 의 대가 항목대로
  조회 방식을 다시 설계한다.
- 이미지 리사이징·크롭 UI. 원본을 그대로 저장한다.
- 고아 오브젝트 lifecycle 정리 — 기존 후속 과제 유지. 다만 presigned 발급이 없어지면
  "발급받고 등록하지 않은 업로드"라는 고아 발생 경로 자체가 사라진다.
