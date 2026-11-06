# ADR-005. AI 요청을 Kafka 아웃박스로 `ai-worker` 에 전달한다

- 상태: 적용 예정 (구현 중)
- 범위: `ai` 도메인 (app-mvc + `ai-worker`)

## 맥락

웹에서 올린 글·이미지를 Claude Code(`claude -p`)가 읽고 상품·일정을 제안한다. 한 건에 수십 초가 걸리고,
CLI 는 임의 셸을 실행할 수 있는 에이전트라 웹 프로세스 안에서 돌리면 안 된다. 그래서 별도 컨테이너
(`ai-worker`)가 필요하고, 요청을 안전하게 넘기는 통로가 필요하다. ADR-003 은 "Kafka 는 쓰기 전에 ADR 을
먼저 쓴다" 고 했다.

## 결정

- **Kafka `ai.request.v1`(키 = `requestId`)** 로 요청 이벤트를 보낸다. 이벤트는 `{eventId, requestId, type, version}` 만
  담고 이미지는 MinIO 키로 참조한다.
- **트랜잭셔널 아웃박스.** 요청과 `ai_outbox` 행을 한 트랜잭션에 저장하고, 발행기가 `FOR UPDATE SKIP LOCKED` 로
  가져가 보낸 뒤 상태를 바꾼다. 프로듀서는 `enable.idempotence=true`, `acks=all`.
- **전달은 at-least-once, 소비는 멱등.** 워커는 app-mvc 내부 API 로 claim(CAS + 리스)한 뒤 처리한다.
  중복 전달은 claim 409 로 건너뛴다. `attempt` 번호로 늦은 보고를 막는다(펜싱).
- **재시도 토픽 없음.** 실패는 같은 경로(outbox → `ai.request.v1`)로 백오프 후 다시 보낸다. 3회 실패는 `DEAD` +
  `ai.request.dlq` 기록, 수동 재시도 API 를 둔다.
- **직렬 처리.** 파티션 1·소비자 1·병렬 없음. 한 사용자는 진행 중 요청을 하나만 가진다.
- **AI 는 제안만 한다.** 반영은 사용자 승인 뒤 app-mvc 가 요청자 권한으로 기존 서비스를 호출한다.

## 기각한 대안

- **DB 폴링만**: 단순하지만 사용자가 Kafka 를 요청했고, 발행 상태·멱등성·재처리를 직접 설계하는 것이 목적이었다.
- **Redis 리스트/pub-sub**: 상태 추적이 약하고 pub/sub 는 구독자가 없으면 메시지가 사라진다.
- **app-mvc 가 워커에 HTTP 푸시**: 워커에 인바운드 포트가 생기고 워커가 꺼져 있으면 요청이 유실된다.
- **Kafka 트랜잭션 exactly-once**: 외부 시스템(app-mvc, Claude)이 끼어 있어 얻는 것이 없다.

## 영향

- app-mvc 에 `spring-kafka` 가 들어간다. 브로커가 없어도 부팅과 접수는 된다(발행만 밀린다).
- 로컬 브로커는 1대·복제 1 이라 내구성은 개발 수준이다. 컨테이너는 `woobeee-kafka:29092`(PLAINTEXT), 로컬 실행은 SASL `localhost:8011`.
- 이 ADR 이 쓰는 Kafka 는 `ai` 한정이다. 다른 도메인에서 쓰려면 새 ADR 을 쓴다.
