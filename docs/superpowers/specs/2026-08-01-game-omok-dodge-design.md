# 게임 도메인 설계 — 오목 · 장애물피하기 · 상단탭 · 마이페이지

- 작성일: 2026-08-01
- 대상: `app-webflux` game 도메인(신규), `app-mvc` Flyway(`V2__game.sql`), `front` 전역 내비게이션과 게임/마이페이지 화면
- 선행 spec: `2026-08-01-member-consolidation-profile-image-design.md` (단일 `Member`, `ROLE_MEMBER`, 프로필 이미지 presigned 계약)

## 배경

이 spec을 작성할 당시 `docs/game/PRD.md` 는 "미작성 — 기능 설계는 후속 spec에서 다룬다" 상태였고,
`app-webflux` 에는 `GET /api/game/health` 와 `GET /api/game/me` 골격만 있었다. `CLAUDE.md` 의 후속
과제 표도 실시간 통신 방식·게임 규칙·`V2__game.sql` 세 가지를 이 spec으로 미뤄 두었다. 이 문서가
그 셋을 모두 정했다. (지금은 구현이 끝나 `docs/game/PRD.md` 의 AC 표가 채워져 있다 — 그 표가
단일 출처다. 아래 "테스트" 절 참고.)

동시에 front는 폐기된 art-marketplace 화면(`app/products`, `app/cart`, `app/chat`)이 남아 있고
전역 내비게이션이 그 구조를 따르고 있다. 게임으로 전환하면서 상단탭을 다시 잡는다.

## 목표

1. 방을 만들고 초대 링크로 사람을 모아 실시간으로 게임하는 기반을 만든다.
2. 오목(1:1 턴제)과 장애물피하기(최대 8인 격자 액션) 두 게임을 제공한다.
3. 회원과 비회원(닉네임만 입력)이 같은 방에서 함께 플레이한다.
4. 종료된 게임의 결과를 남기고 기보를 다시 볼 수 있게 한다.
5. 상단탭(홈·기술블로그·마이페이지)과 마이페이지를 만든다.

## 비목표

- **수평 확장.** 게임 상태는 단일 `app-webflux` 인스턴스의 인메모리에 있다. 다중 인스턴스 경로는
  ADR에 기록만 하고 구현하지 않는다.
- **게임 머니 증감.** 선행 spec에서 `gameMoney` 는 항상 0으로 정해 두었다. 승패로 잔액을 움직이는
  계약은 이 spec에서도 만들지 않는다. 마이페이지는 잔액을 표시만 한다.
- **관전 모드**, 랭킹/리더보드, 매치메이킹(방 목록에서 아무 방이나 골라 들어가기).
- **폐기 화면 정리.** `app/products`, `app/cart`, `app/chat` 삭제는 별도 과제다. 이 spec은 상단탭에서
  링크를 걷어내는 데까지만 관여한다.
- Kafka 연동(ADR-003 유지).

## 결정 사항

| 항목 | 결정 | 근거 |
| --- | --- | --- |
| 실시간 통신 | WebSocket 단일 (ADR-005) | 8인 액션에 필요한 저지연 양방향을 만족하는 유일한 선택. 오목도 같은 채널을 써서 방 입퇴장·참가자 목록 코드를 한 벌만 둔다 |
| 상태 소유 | 인메모리 권위 + Redis 레지스트리 | 틱마다 네트워크 왕복이 0. 단일 인스턴스 전제 |
| 비회원 신원 | game 도메인이 게스트 토큰 발급 | auth와 core 토큰 계약(`AuthTokenType`, `TokenMetadata`)을 건드리지 않는다. 선행 spec의 `ROLE_MEMBER` 단일 결정을 유지 |
| 영속화 범위 | 종료된 게임 결과만 DB, 기보는 MinIO 파일 | 진행 중 상태를 DB에 쓰면 8인 틱 루프에서 병목이 된다 |
| 기보 형식 | PRNG 시드 + 입력 로그 (결정론적 재생) | 틱마다 전체 상태를 남기는 것보다 훨씬 작고, 같은 시드로 서버 로직을 그대로 되돌릴 수 있다 |
| S3 클라이언트 | `app-webflux` 는 `S3AsyncClient` | `app-mvc` 의 `S3Client` 는 블로킹이다. WebFlux 모듈에서 쓰면 이벤트 루프를 막는다 |
| 스키마 위치 | `app-mvc/src/main/resources/db/migration/V2__game.sql` | 스키마 단일 소스는 app-mvc Flyway. `app-webflux` 는 R2DBC로 읽고 쓰기만 한다 |
| 오목 규칙 | 15×15 렌주룰, 흑 금수 | 사용자 결정 |
| 장애물피하기 | 12×16 격자 탑다운 배틀로얄 | 사용자 결정. 격자라 서버 상태가 작고 판정이 정수 비교로 끝난다 |
| 홈 라우팅 | 홈(`/`)은 랜딩, 게임 메인은 `/game` | 사용자 결정. 상단탭은 요청대로 3개 |

## 설계

### 1. 아키텍처

game 도메인 전체를 `app-webflux` 가 소유한다. 패키지는 `com.woobeee.game` 아래에 둔다.

```
com.woobeee.game
|-- api/            REST 컨트롤러와 요청·응답 DTO
|-- ws/             WebSocketHandler, 메시지 봉투, 세션 레지스트리
|-- room/           Room, RoomRegistry, 초대코드, 참가자
|-- identity/       GameParticipant, 게스트 토큰 발급·검증
|-- omok/           보드, 렌주룰 판정, 턴 관리
|-- dodge/          격자, 틱 루프, 장애물 생성, 충돌 판정
|-- result/         결과 영속화(R2DBC), 기보 업로드(S3AsyncClient)
`-- security/       기존 ReactiveTokenVerifier, GamePrincipal
```

데이터 경로는 셋으로 갈린다.

- **진행 중 상태** — 프로세스 인메모리. `Room` 객체가 참가자 목록과 게임 상태를 들고 있고,
  브로드캐스트는 방마다 하나씩 두는 `Sinks.Many<ServerMessage>` (multicast, `onBackpressureBuffer`)로 한다.
- **방 레지스트리와 게스트 토큰** — Redis. 초대 링크로 들어온 사람이 방 존재 여부를 확인하고
  게스트 토큰을 받는 경로가 WebSocket 접속 전에 필요하다.
- **종료된 게임** — Postgres(R2DBC) + MinIO(`S3AsyncClient`).

`Room` 상태 변경은 방마다 직렬화해야 한다. 8인이 서로 다른 Netty 이벤트 루프에서 입력을 보내고,
유예 타이머와 틱 루프까지 같은 방을 건드리기 때문이다.

**구현은 `Room` 인스턴스 자체를 모니터로 쓰는 동기화다.** 초안은 방마다 `Sinks.Many<Command>` 입력
큐를 두고 순차 처리하겠다고 적었지만 그 큐는 만들어지지 않았고, 그 사실을 모른 채 `Room` 이
"락 없이 안전하다"고 주석까지 달려 있었다 — 동시 JOIN 이 멤버를 잃고 `members()` 순회가
`ConcurrentModificationException` 을 던지는 실제 레이스였다. 정원·상태 검사와 멤버 추가처럼
검사와 변경이 붙어 있어야 하는 것은 `Room.admit` / `Room.beginGame` 처럼 한 번의 임계 구역 안에서
끝낸다.

**게임 싱크는 자기 상태를 스스로 지켜야 한다.** 큐가 없으므로 `GameCommandSink` 구현체가 들고 있는
게임 객체(예: `OmokGame`)에는 어떤 직렬화도 자동으로 적용되지 않는다.

### 2. 참가자 신원

회원과 게스트를 하나로 추상화한다.

```java
public record GameParticipant(
        String participantId,   // 회원 "m:{memberId}", 게스트 "g:{uuid}"
        String displayName,     // 회원은 nickname, 게스트는 입력한 닉네임
        ParticipantKind kind,   // MEMBER | GUEST
        Long memberId           // GUEST 면 null
) {}
```

`participantId` 에 접두사를 두는 이유는 회원 11번과 게스트가 같은 식별자를 갖는 사고를 타입이 아니라
값에서 막기 위해서다. 결과 테이블에도 이 문자열이 그대로 들어간다.

**회원** — 기존 access token을 `ReactiveTokenVerifier` 로 검증한다. `memberId` 는 얻지만 닉네임은
Redis 토큰에 없으므로, 방 참가 시 `members` 를 R2DBC로 한 번 조회해 `nickname` 을 가져온다.
`members` 는 이 시점부터 두 앱이 공유하는 첫 테이블이 된다(선행 spec이 예고한 상황). **쓰기 소유권은
app-mvc 단독이고 app-webflux는 읽기만 한다** — 이 spec에서 그렇게 고정한다.

**게스트** — 방 참가 직전에 `POST /api/game/rooms/{roomId}/guest-tokens` 로 발급받는다. 요청에는
`inviteCode` 와 `nickname` 이 필요하다. 서버는 불투명 토큰을 만들어 Redis에 저장한다.

```
key   game:guest:{token}
value hash { participantId, displayName, roomId }
TTL   방 수명과 동일 (기본 6시간, 방 소멸 시 삭제)
```

게스트는 DB에 남지 않는다. 게임이 끝나면 `game_result_participants` 에 `member_id = null` 로
표시 이름만 기록된다.

**닉네임 검증** — 1~20자, 앞뒤 공백 제거, 제어문자 금지. 같은 방 안에서 중복이면 `409`.
회원 닉네임과 게스트 닉네임은 같은 규칙으로 같은 공간에서 겹치는지 본다.

### 3. 방

| 항목 | 오목 | 장애물피하기 |
| --- | --- | --- |
| 정원 | 2 (방장 포함) | 8 (방장 포함) |
| 시작 조건 | 2명이 모두 READY | 2명 이상이 모두 READY |
| 시작 권한 | 방장 | 방장 |

- **생성** — `POST /api/game/rooms {gameType}`. 회원만 만들 수 있다. `roomId`(불투명 22자)와
  `inviteCode`(8자)를 발급한다.
- **초대 링크** — `/game/{gameType}/{roomId}?invite={inviteCode}`. `inviteCode` 를 `roomId` 와 따로 두는
  이유는 `roomId` 가 로그·URL에 남아도 그것만으로는 입장할 수 없게 하기 위해서다.
- **연결 끊김과 이탈을 구분한다.** 이 둘을 같게 다루면 "재접속 허용"과 "이탈 즉시 처리"가 서로
  모순된다. WebSocket이 끊기면 참가자를 방에서 빼지 않고 `DISCONNECTED` 로 표시하고 **30초 유예**를
  준다. 유예 안에 재접속하면 기존 자리를 그대로 잇고, 넘기면 이탈이 확정된다. 반대로 클라이언트가
  보낸 명시적 `LEAVE` 는 유예 없이 즉시 이탈이다.
- **재접속** — 토큰이 유효하고 그 `participantId` 가 이미 방에 있으면 자리를 잇는다. 이전 WebSocket
  세션은 닫는다(한 참가자당 세션 1개). 재접속 시 서버가 현재 전체 상태를 한 번 내려보낸다.
- **난입 불가** — 게임이 `IN_PROGRESS` 면 새 `participantId` 의 `JOIN` 은 `409` 로 거절한다.
  재접속은 새 참가가 아니므로 허용된다.
- **방장 이탈 시 위임** — 방장이 나가면 참가 순서상 다음 사람이 방장이 된다. 게임 중이면 게임은
  계속된다.
- **방 소멸** — 참가자가 0이 되면 즉시 소멸. 아무도 없는 채로 남지 않는다. 그 외에 생성 후
  6시간이 지나면 강제 소멸한다.
- **게임 중 이탈 확정 시** — 오목은 남은 사람의 승리로 종료. 장애물피하기는 그 참가자를 탈락
  처리하고 게임을 계속한다. 유예 중에도 게임은 멈추지 않는다: 오목의 수당 제한시간은 계속 흐르고,
  장애물피하기는 입력이 없으니 제자리에 서 있다가 대개 부딪혀 탈락한다.

### 4. WebSocket 프로토콜

엔드포인트는 `/ws/game` 하나다. `WebSocketHandler` 를 `HandlerMapping` 에 등록한다.

**인증** — 브라우저 WebSocket은 핸드셰이크에 `Authorization` 헤더를 붙일 수 없다. 토큰은 첫 메시지로
받는다. 연결 직후 `JOIN` 이 오기 전까지 세션은 아무 방에도 속하지 않고, 10초 안에 `JOIN` 이 없으면
서버가 닫는다. 쿼리 파라미터로 토큰을 넘기지 않는다 — URL은 접근 로그에 남는다.

모든 메시지는 봉투 하나로 통일한다.

```json
{ "type": "OMOK_PLACE", "seq": 12, "payload": { "x": 7, "y": 7 } }
```

`seq` 는 클라이언트가 증가시키는 번호다. 서버는 응답에 `ackSeq` 를 실어 보내 클라이언트가 자기
입력의 처리 결과를 짝지을 수 있게 한다.

`participants[]` 의 각 항목은 `{participantId, displayName, kind, ready, connection}` 이고
`connection` 은 `CONNECTED` / `DISCONNECTED` 다. 사이드바의 참가자 목록이 이 값을 그린다.

**클라이언트 → 서버**

| type | payload | 설명 |
| --- | --- | --- |
| `JOIN` | `roomId`, `inviteCode`, `token` | 최초 1회. 회원 access token 또는 게스트 토큰 |
| `LEAVE` | — | 명시적 퇴장 |
| `READY` | `ready` | 준비 토글 |
| `START` | — | 방장만. 시작 조건 미충족이면 `ERROR` |
| `OMOK_PLACE` | `x`, `y` | 착수 |
| `DODGE_MOVE` | `direction` | `UP`/`DOWN`/`LEFT`/`RIGHT` |

**서버 → 클라이언트**

| type | payload | 언제 |
| --- | --- | --- |
| `ROOM_STATE` | `gameType`, `hostParticipantId`, `participants[]`, `status` | 입퇴장·READY·연결상태 변화 때마다 방 전체에 |
| `GAME_START` | `startedAt`, 게임별 초기 상태 | 시작 시 |
| `OMOK_MOVED` | `participantId`, `x`, `y`, `color`, `nextTurn`, `turnDeadline` | 착수 성공. **승리 착수는 예외** — 다음 차례가 없으므로 `nextTurn`/`turnDeadline` 없이 네 필드(`participantId`, `x`, `y`, `color`)만 싣고, 뒤이어 `GAME_END` 가 나간다 |
| `OMOK_REJECTED` | `ackSeq`, `reason` | 금수·차례아님·이미 놓인 자리 |
| `DODGE_TICK` | `tick`, `positions[]`, `obstacles[]`, `eliminated[]` | 매 틱, 방 전체에 |
| `GAME_END` | `winnerParticipantId`, `ranks[]` | 종료 시 |
| `ERROR` | `ackSeq`, `code`, `message` | 처리 실패 |

`GAME_END` 에 `gameResultId` 를 싣지 않는다. 결과 id 는 DB 기록과 기보 업로드가 끝나야 나오는데,
업로드가 느리거나 타임아웃 나는 동안 플레이어가 승패를 못 보는 편이 훨씬 나쁘다. 종료는 즉시
알리고, 결과 id 가 필요한 화면은 전적 목록 API 로 따로 받는다.

**연결 종료** — 세션이 끊기면 그 참가자를 `DISCONNECTED` 로 바꿔 `ROOM_STATE` 를 브로드캐스트하고
30초 유예 타이머를 건다. 유예가 만료되면 방에서 빼고 다시 `ROOM_STATE` 를 브로드캐스트한다.

### 5. 오목 (턴제, 서버 판정)

15×15. 흑 선. 방장이 흑, 나중에 들어온 사람이 백이다.

**렌주룰** — 흑에게만 금수가 있다.

| 금수 | 정의 |
| --- | --- |
| 삼삼 | 그 수로 **열린 삼**이 2개 이상 생기면 금수 |
| 사사 | 그 수로 **사**가 2개 이상 생기면 금수 |
| 장목 | 그 수로 6목 이상이 생기면 금수 |

승리 조건은 색깔마다 다르다. **흑은 정확히 5목**일 때만 승리한다(6목은 장목 금수). **백은 5목
이상**이면 승리하고 금수가 없다.

**열린 삼 판정의 재귀** — "열린 삼"은 한 수를 더해 **열린 사**를 만들 수 있는 삼이다. 그런데 그
한 수를 두는 자리가 다시 금수라면 열린 사를 만들 수 없으므로 열린 삼이 아니다. 즉 금수 판정이
자기 자신을 부른다. 구현에서는 재귀 깊이를 제한하고(기본 5), 깊이를 넘으면 금수가 아닌 것으로
본다. 깊이 5를 넘는 중첩 금수는 실전에서 나오지 않는다.

**이 spec에서 가장 무거운 단일 로직이 여기다.** 오목 서버 코드의 절반 이상을 차지할 것으로 보고,
AC와 테스트를 금수 케이스 위주로 두껍게 잡는다.

**금수 착수 시도** — 정통 렌주룰은 흑이 금수를 두면 즉시 패배지만, 이 게임은 **서버가 거절하고
판 상태를 바꾸지 않는다**(`OMOK_REJECTED`). 착수 전에 금수 자리를 클라이언트에 표시해 줄 수 없는
구조가 아니므로, 실수로 지는 경험을 만들 이유가 없다.

**제한시간** — 수당 60초. 초과하면 시간 초과한 쪽이 패한다. 서버가 타이머를 소유하고,
`turnDeadline` 을 `OMOK_MOVED` 에 실어 보내 클라이언트가 카운트다운을 그린다.

### 6. 장애물피하기 (틱 루프, 서버 권위)

12열 × 16행 격자. 참가자는 최하단 행(`y=15`)에 균등 간격으로 배치한다.

**틱** — 100ms(10 tick/s). 매 틱 순서는 고정이다.

1. 큐에 쌓인 입력을 반영한다. 참가자당 **틱당 1회**만 반영하고, 여러 번 왔으면 마지막 것을 쓴다.
   격자 밖으로 나가는 이동은 무시한다.
2. 모든 장애물을 1칸 내린다. 최하단을 벗어난 장애물은 제거한다.
3. 신규 장애물을 최상단(`y=0`)에 생성한다.
4. 충돌을 판정한다.
5. `DODGE_TICK` 을 브로드캐스트한다.

**장애물 생성** — 매 틱, 각 열에 대해 독립적으로 굴린다. 확률은 시작 15%에서 10초마다 5%p씩 올라
최대 60%에서 멈춘다. 난수는 **방마다 고정 시드의 PRNG**를 쓴다(기보 재생을 위해).

**PRNG은 xorshift32로 고정한다.** 기보 재생은 클라이언트(JS)가 서버(Java)와 같은 난수열을 만들어야
성립하는데, `java.util.Random` 을 쓰면 JS가 48비트 LCG를 재구현해야 한다. 양쪽 10줄로 같은 결과가
나오는 것을 쓴다. 상태는 32비트 부호 없는 정수 하나다.

```
next(x):
  x ^= x << 13
  x ^= x >>> 17
  x ^= x << 5
  return x                       // 모든 연산은 32비트로 자른다
nextFloat(x) = toUnsigned32(next(x)) / 4294967296.0    // [0, 1)
```

Java는 `int` 산술이 이미 32비트로 순환하므로 그대로 쓰고, 부호 없는 값이 필요할 때만
`Integer.toUnsignedLong` 을 쓴다. JS는 각 시프트 뒤에 `| 0`, 마지막에 `>>> 0` 을 붙인다.
시드는 0이면 안 된다(xorshift는 0에서 멈춘다). 방 생성 시 0을 뽑으면 1로 바꾼다.

열 순회 순서도 난수열의 일부다. **열 0부터 11까지 오름차순으로 굴린다**고 고정한다.

**충돌** — 이동과 하강이 모두 끝난 뒤 같은 칸에 있으면 탈락이다. 여기에 더해 **스왑도 충돌로
본다**: 참가자가 위로 올라가고 장애물이 내려와 서로 지나친 경우, 격자 위에서는 겹치지 않지만
실제로는 부딪힌 것이므로 탈락 처리한다. 이 케이스를 빠뜨리면 위로 이동해 장애물을 통과하는
버그가 된다.

**순위** — 탈락 순서의 역순이다. 마지막 생존자가 1위. 같은 틱에 여러 명이 탈락하면 공동 순위다.
생존자가 1명이 되면 종료하고, 마지막 2명이 같은 틱에 탈락하면 공동 1위로 종료한다.

### 7. 영속화

**`V2__game.sql`** (`app-mvc`에 추가)

```sql
CREATE TABLE game_results (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    game_type VARCHAR(20) NOT NULL,
    room_id VARCHAR(40) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NOT NULL,
    winner_participant_id VARCHAR(64),
    replay_object_key VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE game_result_participants (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    game_result_id BIGINT NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    member_id BIGINT,
    finish_rank INT NOT NULL
);

CREATE INDEX idx_game_results_ended_at ON game_results (ended_at DESC);
CREATE INDEX idx_game_result_participants_result ON game_result_participants (game_result_id);
CREATE INDEX idx_game_result_participants_member ON game_result_participants (member_id);
```

`finish_rank` 로 쓴 것은 `rank` 가 SQL 윈도우 함수 이름과 겹쳐 읽기 나쁘기 때문이다.
`member_id` 에 FK를 걸지 않는다 — 게스트는 `null` 이고, `members` 는 app-mvc가 소유하는 테이블이라
game 쪽에서 참조 무결성을 강제하면 회원 삭제 경로가 game에 묶인다.

**기보 파일** — `games/{gameType}/{gameResultId}.ndjson`, MinIO에 종료 시 한 번에 업로드한다.
첫 줄이 헤더, 이후가 이벤트다.

오목:
```
{"v":1,"gameType":"OMOK","boardSize":15,"players":[{"participantId":"m:11","color":"BLACK","displayName":"..."}]}
{"t":1,"p":"m:11","x":7,"y":7}
{"t":2,"p":"g:ab12","x":7,"y":8}
```

장애물피하기 — 시드와 입력만 남기고 재생 시 같은 로직으로 재현한다.
```
{"v":1,"gameType":"DODGE","cols":12,"rows":16,"tickMs":100,"seed":8412739,"players":[...]}
{"tick":3,"moves":{"m:11":"LEFT","g:ab12":"RIGHT"}}
{"tick":7,"moves":{"m:11":"UP"}}
```

입력이 없는 틱은 줄을 남기지 않는다. 3분짜리 8인 게임도 파일이 수십 KB에 그친다.

**업로드 실패** — 결과 행을 먼저 쓰고(`replay_object_key = null`) 업로드에 성공하면 key를 채운다.
업로드가 실패해도 전적은 남고 다시보기만 불가능하다. 경고 로그를 남긴다. 프로필 이미지에서 쓴
것과 같은 방향(본체 먼저 커밋, 스토리지는 뒤)이다.

### 8. REST 엔드포인트

기존 `GET /api/game/health`, `GET /api/game/me` 에 더한다.

| 기능 | 메서드 · 경로 | 인증 | 요청 → 응답 |
| --- | --- | --- | --- |
| 방 생성 | `POST /api/game/rooms` | 회원 | `{gameType}` → `{roomId, inviteCode, gameType}` |
| 방 요약 | `GET /api/game/rooms/{roomId}?invite=` | 공개 | — → `{gameType, status, capacity, participantCount}` |
| 게스트 토큰 발급 | `POST /api/game/rooms/{roomId}/guest-tokens` | 공개 | `{inviteCode, nickname}` → `{token, participantId, displayName}` |
| 내 전적 목록 | `GET /api/game/me/results` | 회원 | 페이징 → 결과 목록 |
| 기보 다시보기 | `GET /api/game/results/{id}/replay` | 회원(참가자 본인) | — → `{replayUrl}` presigned GET |

방 요약을 공개로 두는 이유는 초대 링크를 받은 사람이 로그인/게스트를 고르기 전에 "무슨 게임인지,
자리가 남았는지"를 봐야 하기 때문이다. `inviteCode` 가 맞아야 응답한다.

전적 목록 조회는 `game_result_participants` 를 `member_id` 로 걸고 `game_results` 와 조인해 한 번에
가져온다(네이티브 SQL, N+1 없이). `CLAUDE.md` 의 쿼리 규칙을 따른다.

### 9. 프론트

**상단탭** — 홈 / 기술블로그 / 마이페이지 세 개. 현재 `components/header.tsx` 의 장바구니·상품 링크를
걷어낸다. 게임으로 들어가는 진입점은 홈 랜딩의 CTA다.

| 경로 | 화면 |
| --- | --- |
| `/` | 랜딩. `/game` 으로 가는 CTA |
| `/blog` | 기존 기술블로그 |
| `/mypage` | 프로필 + 전적 + 기보 다시보기 |
| `/game` | 게임 메인. 오목·장애물피하기 카드 2장 |
| `/game/omok/[roomId]` | 오목 플레이 |
| `/game/dodge/[roomId]` | 장애물피하기 플레이 |

**플레이 화면 레이아웃** — 보드를 최대한 크게 잡는다. 데스크톱은 보드가 좌측에서 가용 높이를
꽉 채우고(정사각 유지) 우측에 폭 고정 사이드바(초대 링크 복사 버튼 + 참가자 목록 + READY/START).
좁은 화면에서는 보드가 위, 사이드바가 아래로 쌓인다.

**초대 링크 진입 흐름** — `?invite=` 가 있는 URL로 들어오면 방 요약을 먼저 조회하고, 로그인 상태면
바로 참가, 아니면 "로그인 / 닉네임으로 참가" 두 갈래를 보여준다. 닉네임을 넣으면 게스트 토큰을
발급받아 WebSocket `JOIN` 으로 넘어간다.

**WebSocket 클라이언트** — `front/lib/game-socket.ts` 에 재연결(지수 백오프, 최대 5회)과 `seq` 관리를
한 곳으로 모은다. 게임 화면 컴포넌트는 이벤트만 구독한다.

**마이페이지** — 세 블록이다.

1. **프로필** — `GET /api/auth/me`(app-mvc). 이미지·닉네임·게임머니. 이미지 업로드는 선행 spec에서
   만든 presigned PUT 2-step을 그대로 쓴다.
2. **전적 목록** — `GET /api/game/me/results`(app-webflux). 게임종류·일시·순위·상대.
3. **기보 다시보기** — 목록 항목에서 열면 `GET /api/game/results/{id}/replay` 로 presigned URL을 받아
   ndjson을 내려받고, 클라이언트에서 재생한다. 오목은 수를 순서대로 놓으며 되감기/빨리감기를
   제공하고, 장애물피하기는 시드와 입력으로 **서버와 같은 틱 로직을 클라이언트에서 다시 돌린다**.
   그래서 틱 로직은 서버 구현과 규칙이 한 글자도 달라지면 안 된다 — 격자 크기·틱 간격·장애물
   생성 확률 곡선을 기보 헤더에 실어 보내는 이유가 이것이다.

front rewrites가 두 백엔드를 이미 프록시하므로 모두 단일 오리진으로 호출된다.

## 테스트

인수 기준(AC)의 단일 출처는 `docs/game/PRD.md` 의 `## 인수 기준 (Acceptance Criteria)` 표다
(`CLAUDE.md` 규칙). 이 spec은 그 표를 되풀이해 적지 않는다 — 여기 별도 테이블을 두면 둘이
갈라졌을 때 어느 쪽이 맞는지 알 수 없어진다. 계획 단계에서 이 spec이 초안으로 삼았던 GAME-AC-01
~ GAME-AC-22 항목과 그 커버 테스트는 전부 `docs/game/PRD.md` 로 옮겨 갱신했다.

- 게임 로직(`omok`, `dodge`)은 WebSocket·Redis·DB 없이 순수 단위 테스트로 검증한다. 이 둘이
  프레임워크에 의존하지 않게 설계하는 것이 테스트 용이성의 핵심이다.
- Redis는 임베디드 대신 `ReactiveStringRedisTemplate` 목으로 검증한다. S3는 `S3AsyncClient` 목.
- 틱 루프는 실시간 대기 없이 테스트할 수 있어야 한다. `Flux.interval` 을 직접 쓰지 말고 주입받은
  스케줄러/틱 소스를 쓰거나, 틱 진행을 `advanceOneTick()` 같은 순수 메서드로 분리해 루프와
  로직을 나눈다.
- `SchemaValidationTest` 는 game 테이블에 JPA 엔티티가 없으므로 영향받지 않는다. V2 적용 후에도
  통과해야 한다.
- `AuthTokenTypeTest` 는 토큰 계약 무변경이므로 그대로 통과해야 한다.

## 함께 갱신할 문서

- `docs/game/PRD.md` — 목표, 회원/게스트 모델, 엔드포인트 표, WebSocket 프로토콜, AC 표
- `docs/_global/adr/ADR-005-realtime-websocket.md` — 신규. WebSocket 선택과 단일 인스턴스 전제,
  다중 인스턴스로 갈 때의 경로(Redis Pub/Sub 중계)
- `docs/FRONTEND.md` — 상단탭 구조와 게임/마이페이지 라우트
- `docs/ARCHITECTURE.md` — game 도메인 구조, `members` 를 두 앱이 공유한다는 사실과 쓰기 소유권
- `docs/auth/PRD.md` — `members` 읽기 소유권이 app-webflux로 넓어졌다는 한 줄
- `CLAUDE.md` — API 엔드포인트 표, 후속 과제에서 "게임 도메인 설계" 항목 정리, 모듈 경계 표에
  `app-webflux` 의 `S3AsyncClient` 언급

## 검증 명령

```bash
./mvnw -pl core,app-mvc,app-webflux -am test
cd front && npm run build && npx tsc --noEmit
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

`app-webflux` 가 블로킹 의존을 들이지 않았는지도 본다.

```bash
./mvnw -pl app-webflux dependency:tree \
  | grep -E "spring-boot-starter-jdbc|spring-boot-starter-data-jpa|org\.postgresql:postgresql:|awssdk:apache-client" \
  && echo "FAIL: blocking client leaked into app-webflux" || echo "OK"
```

아티팩트 이름으로 잡으면 안 되는 것 둘을 짚어 둔다. `spring-boot-starter-data-redis` 는 `core` 가
가져오는 의존이고 `ReactiveStringRedisTemplate` 이 거기서 나오므로 누출이 아니다. `S3Client` 와
`S3AsyncClient` 는 **같은 `awssdk:s3` 아티팩트**에 들어 있어 아티팩트로는 구분되지 않는다 —
실제로 갈라지는 것은 HTTP 클라이언트이므로 `apache-client` 의 부재로 판정한다. 그래서
`app-webflux` 의 `s3` 의존은 `apache-client` 를 **exclusion 으로 끊고** `netty-nio-client` 만 남긴다.

## 미해결 / 후속

| 항목 | 내용 |
| --- | --- |
| 수평 확장 | 단일 인스턴스 전제다. 늘리려면 방 소유권 관리 + Redis Pub/Sub 중계가 필요하다 |
| 게임 머니 증감 | 승패로 잔액을 움직이는 계약. 부정 방지·멱등성·이력 보존을 별도 spec에서 |
| 관전 모드 | 정원 밖 인원이 보기만 하는 경로 |
| 게스트 전적 | 게스트는 `member_id` 가 없어 마이페이지가 없다. 결과 행에 이름만 남는다 |
| 폐기 화면 삭제 | `app/products`, `app/cart`, `app/chat` 제거 |
| 방 목록 / 매치메이킹 | 지금은 초대 링크로만 만난다 |
