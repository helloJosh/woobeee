# 회원 역할(ADMIN/MEMBER) 도입 설계

- 날짜: 2026-08-05
- 상태: 승인됨
- 범위: 저장 + 토큰 반영 (권한 분기·승격 API·프론트 노출은 범위 밖)

## 배경

`members` 테이블/엔티티에 역할 개념이 없고, 토큰 발급 시 `AuthService`에
`"ROLE_MEMBER"` 문자열이 하드코딩되어 있다. 토큰 계약(`core`의 `TokenMetadata.role`)과
app-webflux의 `GamePrincipal`은 이미 role 문자열을 실어 나르므로, 파이프는 있고
출처만 하드코딩인 상태다. 회원별로 ADMIN/MEMBER를 구분해 저장하고, 토큰의 role이
회원의 실제 role에서 파생되도록 한다.

## 결정

### 1. 스키마 — `V4__member_role.sql`

```sql
ALTER TABLE members
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
    CONSTRAINT members_role_check CHECK (role IN ('MEMBER', 'ADMIN'));
```

- 기존 행은 DEFAULT로 전부 `MEMBER`가 된다.
- ADMIN 지정은 당분간 DB 수동 UPDATE 경로뿐이므로 오타를 막는 CHECK 제약을 함께 둔다.

### 2. 엔티티 — `MemberRole` enum + `Member.role`

- `com.woobeee.mvc.auth.entity.MemberRole` — `MEMBER`, `ADMIN`.
  토큰 문자열 변환용 `authority()` 메서드(`"ROLE_" + name()`)를 여기 둔다.
- `Member`에 `@Enumerated(EnumType.STRING)` role 필드(`nullable = false, length = 20`) 추가.
- `Member.create(...)`는 항상 `MemberRole.MEMBER`로 저장한다 — 회원가입 기본값.

### 3. 토큰 반영 — `AuthService`

- signup/login의 `tokenService.issue(member.getId(), ROLE_MEMBER, ...)` 하드코딩을
  `member.getRole().authority()`로 교체한다.
- `AuthService.ROLE_MEMBER` 상수는 제거하고, 참조하던 테스트 3개
  (`AuthServiceTest`, `AuthControllerTest`, `TokenGenerateControllerTest`)는
  `MemberRole.MEMBER.authority()`로 이전한다.
- 토큰에 실리는 값은 기존과 동일한 `"ROLE_MEMBER"` 문자열이므로
  **토큰 계약(`TokenMetadata`)과 app-webflux 검증 쪽은 변경이 없다.**

### 4. AC + 테스트

`docs/auth/PRD.md` 인수 기준 표에 두 줄을 추가하고 그 AC에서 테스트를 도출한다.

| ID | 기준 |
| --- | --- |
| AUTH-AC-15 | 회원가입으로 생성된 회원의 role은 `MEMBER`다 |
| AUTH-AC-16 | 토큰의 role은 회원의 role에서 파생된다 (ADMIN 회원 로그인 시 `ROLE_ADMIN`) |

- `AuthServiceTest`에 두 AC를 커버하는 테스트를 추가한다.
- 엔티티↔마이그레이션 일치는 기존 `SchemaValidationTest`(JPA `validate`)가 잡는다.

## 범위 밖

- ADMIN 전용 엔드포인트 보호(권한 분기)
- 관리자 승격/강등 API
- 프론트 노출 (`GET /me` 응답 등)
