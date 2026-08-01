# PRD — game (게임)

`game` 도메인은 방을 만들고 초대 링크로 사람을 모아 실시간으로 겨루는 기능을 담당한다.

- 베이스 경로: `/api/game`, WebSocket `/ws/game`
- 코드: `com.woobeee.game` (`app-webflux`)
- 설계: [`../superpowers/specs/2026-08-01-game-omok-dodge-design.md`](../superpowers/specs/2026-08-01-game-omok-dodge-design.md)
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 회원이 방을 만들고 초대 링크로 사람을 모은다.
- 회원과 비회원(닉네임만 입력)이 같은 방에서 함께 플레이한다.
- 오목(1:1)과 장애물피하기(최대 8인)를 제공한다.
- 종료된 게임의 결과를 남기고 기보를 다시 본다.

## 참가자 모델

- `GameParticipant(participantId, displayName, kind, memberId)` 하나로 회원과 게스트를 다룬다.
- `participantId` 는 회원 `m:{memberId}`, 게스트 `g:{uuid}`.
- 회원은 auth 가 발급한 access token 을 공유 Redis 로 검증한다. 닉네임은 `members` 를 R2DBC 로
  읽어 온다 — **`members` 쓰기 소유권은 app-mvc 단독이고 game 은 읽기만 한다.**
- 게스트 토큰은 game 도메인이 발급해 Redis 에 6시간 TTL 로 둔다. auth 와 core 토큰 계약은
  건드리지 않는다.

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 | 인증 |
| --- | --- | --- |
| health | `GET /api/game/health` | 공개 |
| 내 토큰 확인 | `GET /api/game/me` | 회원 |
| 방 생성 | `POST /api/game/rooms` | 회원 |
| 방 요약 | `GET /api/game/rooms/{roomId}?invite=` | 공개 |
| 게스트 토큰 발급 | `POST /api/game/rooms/{roomId}/guest-tokens` | 공개 |
| 실시간 | `WS /ws/game` | 첫 JOIN 메시지의 토큰 |

## 방 규칙

| 항목 | 오목 | 장애물피하기 |
| --- | --- | --- |
| 정원 | 2 | 8 |
| 시작 조건 | 2명 모두 READY | 2명 이상 모두 READY |

- 방 TTL 6시간. 참가자가 0이 되면 즉시 소멸.
- 소켓이 끊기면 `DISCONNECTED` 로 두고 30초 유예. 유예 안에 재접속하면 자리를 잇는다.
- 명시적 `LEAVE` 는 유예 없이 즉시 이탈.
- 게임 시작 후 새 참가자는 받지 않는다. 재접속은 새 참가가 아니므로 허용된다.
- 방장이 빠지면 참가 순서상 다음 사람이 방장이 된다.

## WebSocket 프로토콜

봉투는 `{type, seq, payload}`. `ackSeq` 는 `ERROR` 에만, 그것도 **게임 명령이 실패했을 때만**
실린다 — 그 경로만 클라이언트의 `seq` 를 넘겨받기 때문이다. `JOIN` / `READY` / `START` 가 실패해
나가는 `ERROR` 에는 `ackSeq` 가 없다. `ROOM_STATE`, `GAME_START` 등 방 전체에 뿌리는 메시지도
특정 명령의 응답이 아니므로 `ackSeq` 가 없다.

- 클라이언트 → 서버: `JOIN` `LEAVE` `READY` `START` `OMOK_PLACE` `DODGE_MOVE`
- 서버 → 클라이언트: `ROOM_STATE` `GAME_START` `OMOK_MOVED` `OMOK_REJECTED` `DODGE_TICK`
  `GAME_END` `ERROR`

## 인수 기준 (Acceptance Criteria)

각 항목은 테스트로 커버한다(프로세스 규칙은 `CLAUDE.md`). 동작/계약 변경 시 이 표를 먼저 갱신하고
테스트를 함께 수정한다.

| ID | 인수 기준 (Given–When–Then) | 커버 테스트 |
| --- | --- | --- |
| GAME-AC-01 | 방 생성은 회원만 가능하고 `roomId` 와 `inviteCode` 를 발급한다 | `RoomControllerTest` |
| GAME-AC-02 | `inviteCode` 가 틀리면 방 요약과 게스트 토큰 발급이 `403` 을 반환한다 | `RoomServiceTest`, `RoomControllerTest` |
| GAME-AC-03 | 게스트 토큰 발급은 닉네임을 요구하고, 같은 방에 중복 닉네임이면 `409` 를 반환한다 | `GuestIdentityServiceTest` |
| GAME-AC-04 | 정원이 찬 방에 `JOIN` 하면 거절한다 (오목 2, 장애물 8) | `RoomServiceTest` |
| GAME-AC-05 | 게임이 `IN_PROGRESS` 면 새 참가자의 `JOIN` 은 거절하고, 기존 참가자의 재접속은 허용한다 | `RoomServiceTest` |
| GAME-AC-06 | 방장이 이탈하면 다음 참가자가 방장이 되고, 참가자가 0이면 방이 소멸한다 | `RoomServiceTest` |
| GAME-AC-07 | `JOIN` 없이 10초가 지난 WebSocket 세션은 서버가 닫는다 | `GameWebSocketHandlerTest` |
| GAME-AC-08 | 연결이 끊기면 `DISCONNECTED` 로 두고 30초 안에 재접속하면 자리를 잇는다. 유예를 넘기면 이탈이 확정된다 | `RoomServiceTest`, `GameWebSocketHandlerTest` |
| GAME-AC-09 | 명시적 `LEAVE` 는 유예 없이 즉시 이탈로 처리한다 | `RoomServiceTest`, `GameWebSocketHandlerTest` |
| GAME-AC-10 | 흑의 삼삼·사사·장목 착수는 `OMOK_REJECTED` 로 거절하고 판 상태를 바꾸지 않는다 | 미작성 — Plan 2 |
| GAME-AC-11 | 백은 금수가 없고 6목 이상으로도 승리한다 | 미작성 — Plan 2 |
| GAME-AC-12 | 흑은 정확히 5목일 때만 승리한다 | 미작성 — Plan 2 |
| GAME-AC-13 | 열린 삼 판정은 열린 사를 만드는 자리가 금수면 열린 삼으로 보지 않는다 | 미작성 — Plan 2 |
| GAME-AC-14 | 차례가 아닌 참가자의 착수와 이미 놓인 자리 착수는 거절한다 | 미작성 — Plan 2 |
| GAME-AC-15 | 수당 제한시간을 넘기면 그 참가자가 패한다 | 미작성 — Plan 2 |
| GAME-AC-16 | 틱당 입력은 참가자별 1회만 반영하고, 격자 밖 이동은 무시한다 | 미작성 — Plan 3 |
| GAME-AC-17 | 참가자와 장애물이 서로 지나친 경우(스왑)도 충돌로 판정한다 | 미작성 — Plan 3 |
| GAME-AC-18 | 탈락 역순이 순위이고, 같은 틱 탈락은 공동 순위다 | 미작성 — Plan 3 |
| GAME-AC-19 | 같은 시드와 같은 입력 로그로 재생하면 원본과 같은 결과가 나온다 | 미작성 — Plan 3 |
| GAME-AC-20 | 게임이 끝나면 결과 1행과 참가자 행들을 기록하고 기보를 업로드한다 | 미작성 — Plan 2 |
| GAME-AC-21 | 기보 업로드가 실패해도 결과는 남고 `replay_object_key` 는 `null` 이다 | 미작성 — Plan 2 |
| GAME-AC-22 | 기보 다시보기는 그 게임 참가자 본인에게만 presigned URL을 발급한다 | 미작성 — Plan 2 |

## 비기능 요구사항

- 실시간 통신은 WebSocket([`../_global/adr/ADR-005-realtime-websocket.md`](../_global/adr/ADR-005-realtime-websocket.md)).
- **블로킹 호출 금지.** Redis 는 `ReactiveStringRedisTemplate`, DB 는 R2DBC.
- 게임 상태는 단일 인스턴스 인메모리다. 수평 확장은 ADR-005 의 후속 과제다.
