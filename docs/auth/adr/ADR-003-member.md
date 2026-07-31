# ADR-003. 회원은 Buyer/Seller를 분리된 엔티티로 둔다 (단일 Member 대신)

- 상태: 적용
- 범위: auth

## 맥락

구매자와 판매자는 공통 속성(Google 식별자, 이메일, 닉네임, 약관 동의)과 더불어 서로 다른 속성·권한을 가진다. 단일 `Member`(+role) 테이블로 합칠지, 별도 엔티티로 나눌지 결정해야 한다.

## 결정

**`Buyer`와 `Seller`를 별도 엔티티/테이블로 분리**한다.

- 테이블: `buyers`, `sellers`. 저장소도 `BuyerRepository`, `SellerRepository`로 분리.
- 공통 필드: `googleSubject`(unique), `email`(unique), `nickname`, `termsAgreed`, `privacyPolicyAgreed`, `active`, `createdAt`.
- Seller 전용: `businessRegistrationCertificateUrl`(사업자등록증) 등 판매자 고유 속성.
- 권한 구분은 `MemberType` enum이 담당: `BUYER`→`ROLE_BUYER`, `SELLER`→`ROLE_SELLER`. 토큰 `role`과 권한 판단에 이 문자열을 사용한다.
- 회원 식별은 (도메인, memberId) 조합으로 다룬다. 예: 인증 필터가 role로 분기해 `BuyerRepository`/`SellerRepository`에서 이메일(loginId)을 조회한다([`../../_common/adr/ADR-001-authheader.md`](../../_common/adr/ADR-001-authheader.md)).

## 근거

- 판매자 전용 속성(사업자 정보 등)이 buyer 레코드에 null로 섞이지 않는다.
- 각 역할의 제약/유효성 규칙을 엔티티 단위로 명확히 표현한다.
- 가입 흐름이 `signup/buyers`, `signup/sellers`로 이미 분리되어 있어 모델 분리와 자연스럽게 맞는다.

## 트레이드오프 / 한계

- `memberId`가 도메인(buyer/seller) 내에서만 유일하다. 회원을 가리킬 때 항상 role/타입을 함께 다뤄야 한다.
- 두 역할을 모두 조회하는 로직은 양쪽 저장소를 분기해야 한다(예: 인증 필터의 `resolveLoginId`).
- 공통 속성 변경 시 두 엔티티를 함께 손봐야 한다.

## 대안 (기각)

- 단일 `Member` + `role` 컬럼: 역할별 전용 속성 처리가 지저분해지고(null 컬럼/체크 제약), 판매자 검증 로직이 분기 투성이가 된다.
