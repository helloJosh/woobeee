# ADR-003. Kafka는 로컬 인프라로 구성하되 현재 코드에는 연동하지 않는다

> **상태 갱신 (2026-07-31):** woobeee 재구성에서 Kafka는 **제거**되었다. Kafka는 product 도메인
> 이벤트 전용이었고 product/cart가 폐기되면서 사용처가 사라졌다. 로컬 compose에서도 제외했다.
> 게임 도메인에서 이벤트 스트리밍이 필요해지면 이 ADR을 다시 열어 재평가한다.

- 상태: 보류(provisioned, not integrated)
- 범위: 전역(Global)

## 맥락

향후 주문/예약/이미지 처리 등에서 비동기 이벤트 스트리밍이 필요할 수 있어 로컬 개발 환경에 Kafka 브로커를 미리 마련해 두었다.

## 현재 상태 (코드 기준)

- `.docker-compose/docker-compose.yml`에 KRaft 모드 Kafka 브로커와 `kafka-ui`가 구성되어 있고, SASL_PLAINTEXT 리스너와 JAAS 설정(`.docker-compose/config/kafka_server_jaas.conf`)을 사용한다.
- **백엔드 코드와 `pom.xml`에는 Kafka 의존성·프로듀서·컨슈머가 없다.** 즉 현재 애플리케이션은 Kafka를 사용하지 않는다.
- 도메인 내 비동기 처리는 현재 Spring의 트랜잭션 이벤트(`@TransactionalEventListener`)로 처리한다. 예: 상품 이미지 이동([`product/adr/ADR-001-imageupload.md`](../../product/adr/ADR-001-imageupload.md)).

## 결정

- 지금은 Kafka를 애플리케이션에 연동하지 않는다. 인프라만 유지한다.
- 실제 연동이 필요해지면 별도 ADR로 토픽 설계·프로듀서/컨슈머 책임·전달 보장 수준(at-least-once 등)을 정한 뒤 도입한다.

## 영향

- Kafka 컨테이너가 떠 있지 않아도 현재 애플리케이션 기동/테스트에는 영향이 없다.
- 문서에서 Kafka를 "사용 중"으로 표현하지 않도록 주의한다. 연동 시 이 ADR과 `ARCHITECTURE.md`를 함께 갱신한다.
