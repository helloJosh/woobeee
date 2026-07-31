# ADR-001. 영속 저장소로 PostgreSQL + Spring Data JPA를 쓰고, 커스텀 조회는 로우 쿼리(Native SQL)로 작성한다

- 상태: 적용 (조회 정책 변경: QueryDSL → 로우 쿼리)
- 범위: 전역(Global)

## 맥락

회원·상품·장바구니·블로그는 관계형 도메인 모델이며 트랜잭션 일관성이 핵심이다. 상품 목록 통합 검색, 블로그 게시글 페이징/검색, 카테고리 집계처럼 동적 조건과 집계가 필요한 조회도 존재한다.

이전에는 동적/집계 조회를 QueryDSL repository fragment로 구현했다. 그러나 실행 SQL이 추상화 뒤로 숨어 **N+1 쿼리**가 드러나지 않고, 실제 PostgreSQL로 나가는 쿼리를 예측·튜닝하기 어려웠다. 이를 개선하기 위해 조회 구현 정책을 변경한다.

## 결정

- 영속 저장소는 **PostgreSQL**, ORM은 **Spring Data JPA(Hibernate)** 를 사용한다.
- 커넥션 풀은 `commons-dbcp2`(`BasicDataSource`)를 사용한다 (`application.yaml`의 `spring.datasource.dbcp2`).
- **조회 구현 정책 (변경됨):**
  - 단순 조회(PK/단일 컬럼 등)는 Spring Data JPA 파생 메서드를 유지한다.
  - 그 외 **모든 커스텀 조회(동적 조건·검색·집계·조인/서브쿼리·목록)는 로우 쿼리(Native SQL)로 작성한다.** `@Query(nativeQuery = true)` 또는 그에 준하는 native 실행을 사용한다.
  - **QueryDSL은 더 이상 사용하지 않는다.** 기존 QueryDSL fragment(`*QueryRepositoryImpl` 등)는 로우 쿼리로 마이그레이션 대상이다(아래 "마이그레이션" 참조).
  - 실행되는 SQL을 코드에서 그대로 읽을 수 있어야 한다. 추상화로 SQL을 숨기지 않는다.

### N+1 해결을 전제로 작성한다

- 목록/상세 조회는 연관 데이터를 **조인으로 한 번에** 가져오거나, 필요한 식별자만 모아 **배치(IN) 조회**로 합친다. 컬렉션을 루프 안에서 단건 조회하지 않는다.
- 엔티티 연관은 기본 `LAZY`로 두고, 로우 쿼리 결과를 응답 DTO로 직접 매핑해 의도치 않은 지연 로딩을 막는다.
- 페이징 + 컬렉션 조인이 겹칠 때는 "ID 페이징(루트 페이지 조회) → 해당 ID들의 연관 배치 조회" 2단계로 분리해 카테시안 곱과 N+1을 함께 피한다.
- 새 조회를 추가/변경하면 `show-sql`(또는 로그)로 실행 쿼리 수를 확인해 N+1이 없는지 검증한다.

## 운영 규칙

- 운영/검증 시 Hibernate DDL은 `validate`를 기준으로 한다. (`application.yaml`의 평소 기본값은 `update`이며, 검증 명령에서 `-Dspring.jpa.properties.hibernate.hbm2ddl.auto=validate`로 덮어쓴다.)
- 엔티티 변경은 DB 스키마 변경으로 간주하고, 문서·마이그레이션 계획과 함께 갱신한다.
- `SchemaValidationTest`로 JPA 매핑과 실제 스키마 불일치를 테스트에서 실패 처리한다. 참고용 스키마 스냅샷은 [`docs/_ddl.sql`](../../_ddl.sql).
- 로우 쿼리는 컬럼·테이블명이 스키마와 강하게 결합되므로, 스키마 변경 시 관련 native 쿼리를 함께 점검한다.

## 마이그레이션 (QueryDSL → 로우 쿼리)

- 신규 조회는 처음부터 로우 쿼리로 작성한다.
- 기존 QueryDSL fragment는 점진적으로 native 쿼리로 교체한다. 교체 완료 후 `querydsl-jpa`/`querydsl-apt` 의존성, `QuerydslConfig`(`JPAQueryFactory` 빈), 생성된 Q 타입을 제거한다.
- 영향 받는 기존 구현: 상품 목록(`product/.../ProductQueryRepositoryImpl`), 블로그 검색/집계(`blog/.../PostQueryRepositoryImpl`). 도메인 ADR도 함께 갱신한다.

## 트레이드오프

- 로우 쿼리는 타입 안전성이 없고 컴파일 시점 검증이 약하다 → 쿼리별 테스트로 보완한다.
- DB 종속(PostgreSQL 문법)이 강해진다 → 본 프로젝트는 PostgreSQL 단일 타깃이므로 수용한다.
- 동적 조건이 많은 쿼리는 문자열 조립이 늘어난다 → 조건 분기를 명확히 나누고 바인딩 파라미터를 사용한다(문자열 결합으로 값 주입 금지, SQL 인젝션 방지).
