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
| 내 전적 목록 | `GET /api/game/me/results?limit=&offset=` | 회원 |
| 기보 다시보기 URL | `GET /api/game/results/{gameResultId}/replay` | 회원(그 게임 참가자 본인만) |
| 실시간 | `WS /ws/game` | 첫 JOIN 메시지의 토큰 |

`limit`(기본 20, 최대 50으로 clamp)과 `offset`(기본 0)은 마이페이지의 "더 보기" 페이징이
의존하는 값이다(`GameResultController`).

## 방 규칙

| 항목 | 오목 | 장애물피하기 |
| --- | --- | --- |
| 정원 | 2 | 8 |
| 시작 조건 | 2명 모두 READY | 1명 이상 모두 READY — 혼자서도 시작할 수 있다(연습 모드). 틱 종료 규칙의 1인 예외(아래 장애물피하기 절)와 짝이다 |

- 방 TTL 6시간. 참가자가 0이 되면 즉시 소멸.
- 소켓이 끊기면 `DISCONNECTED` 로 두고 30초 유예. 유예 안에 재접속하면 자리를 잇는다.
- 명시적 `LEAVE` 는 유예 없이 즉시 이탈.
- 게임 시작 후 새 참가자는 받지 않는다. 재접속은 새 참가가 아니므로 허용된다.
- 방장이 빠지면 참가 순서상 다음 사람이 방장이 된다.
- TTL 로 방이 만료되면 `RoomSweeper` 가 `RoomCommandDispatcher.settle` 을 거치지 않고 바로
  치운다. 그래서 진행 중이던 게임의 sink는 그 사실을 알지 못한다. 오목(`OmokGameSink`)에서는
  그 방의 진행 중 게임 상태가 메모리에 남는 정적 누수지만, **장애물피하기(`DodgeGameSink`)에서는
  더 나쁘다** — 틱 타이머가 이미 사라진 방을 계속 돌리다가 끝내 존재하지 않는 방의 결과 행을
  `game_results` 에 쓴다. 조용한 메모리 누수가 아니라 능동적인 오염이다(알려진 미비점 — 이탈로
  인한 즉시 소멸/기권 경로와 다르다).

## 오목 규칙

- 15×15, 렌주룰. 방장이 흑, 나중에 들어온 사람이 백. 흑 선.
- 흑 금수: 삼삼, 사사, 장목(6목 이상). 백은 금수가 없다.
- 흑은 **정확히 5목**일 때만 승리한다. 백은 5목 이상이면 승리한다.
- **사는 완성점이 아니라 네 개 돌의 집합으로 센다.** `.XXXX.` 는 양끝 어디에 놓아도 5가 되지만
  완성되는 돌 집합이 같으므로 사 하나다. 완성점을 세면 사사로 오판한다.
- **그 집합이 실제로 정확히 5목을 만드는지까지 확인해야 한다.** 모양만 보면 안 된다 —
  `...XXXX.X......` 에서 창 `4..8`은 흑 넷과 빈칸 하나로 사 모양을 갖췄지만, 그 빈칸을 채우면
  8번 칸의 흑과 이어져 6목(장목)이 된다. 이런 창을 사로 세면 합법수를 사사로 잘못 거절한다.
  그래서 후보 빈칸에 흑을 임시로 놓아 완성된 길이가 정확히 5인 창만 사로 센다.
- **열린삼도 완성점이 아니라 세 개 돌의 집합으로 센다.** 한 축 위에 열린삼이 두 개 있을 수
  있어서 축마다 참/거짓 하나로 세면 삼삼을 놓친다. 또한 그 완성점이 만드는 열린사가 지금
  판정 중인 자리를 포함하지 않으면 세지 않는다 — 그렇지 않으면 이번 수와 무관하게 판 어딘가에
  이미 있던 삼이 삼삼 판정에 끼어들어 합법수를 거절하게 된다.
- 열린삼 판정은 금수 판정을 재귀 호출한다 — 삼이 "열려" 있으려면 그것을 열린사로 바꾸는 수가
  실제로 둘 수 있어야 하기 때문이다. 재귀 깊이는 5에서 끊는다.
- 금수 착수는 `OMOK_REJECTED` 로 거절하고 판 상태를 바꾸지 않는다(정통 렌주룰의 즉시 패배와 다르다).
- 수당 제한시간 60초. `turnDeadline` 으로 클라이언트에 알린다. **다만 강제되지는 않는다** —
  `OmokGame.timeout(...)` 을 호출하는 배선이 없어서 제한시간을 넘겨도 서버가 자동으로 패배
  처리하지 않는다(알려진 미비점).
- 게임 중 이탈이 확정되면 기권으로 처리해 상대가 이긴다.

## 장애물피하기 규칙

- 12열 × 16행 격자, 최대 8인. 참가자는 최하단 행에 균등 간격으로 배치한다.
- 틱 100ms. 매 틱 순서는 고정이다: 입력 반영 → 장애물 1칸 하강 → 신규 생성 → 충돌 판정 → 브로드캐스트.
- 입력은 **참가자당 틱당 1회**만 반영한다. 여러 번 오면 마지막 것을 쓰고, 격자 밖 이동은 무시한다.
- 장애물 생성 확률은 시작 15%, 100틱(10초)마다 +5%p, 최대 60%. 열 0부터 11까지 오름차순으로 굴린다.
- **스왑도 충돌이다.** 참가자가 위로 올라가고 장애물이 내려와 서로 지나치면 격자상 겹치지 않지만
  부딪힌 것으로 본다. 이 케이스를 빼면 위로 이동해 장애물을 통과하는 버그가 된다.
- 순위는 탈락 역순. 같은 틱 탈락은 공동 순위. 생존자가 1명이 되면 종료한다.
  - **예외: 1인 게임.** "한 명 남음"이 종료인 것은 원래 둘 이상으로 시작한 게임에서만이다.
    참가자가 처음부터 한 명이면 그 한 명이 맞을 때까지 계속되고, 맞는 순간(생존자 0명) 끝난다 —
    그렇지 않으면 1인 게임이 시작하자마자 끝나 버린다. `DodgeGame:130` 이
    `participantIds.size() > 1` 로 이 조건을 걸고 있고
    `DodgeGameTest.aSoloGameEndsWhenItsOnlyParticipantIsHit` 이 고정한다. 브라우저 포트도 같은
    조건이어야 1인 기보의 길이가 서버와 일치한다.
- 게임 중 이탈이 확정되면 그 참가자를 탈락 처리하고 게임은 계속한다.
- 방 명령을 직렬화하는 큐는 없다(오목과 같다). `Room` 은 스스로 동기화하지만 싱크가 들고 있는
  게임 객체는 그 보호를 자동으로 물려받지 않는다 — 그래서 틱 타이머와 최대 8명이 보내는
  `DODGE_MOVE` 가 같은 `DodgeGame` 인스턴스를 서로 다른 스레드에서 건드리는 것을 막기 위해
  `DodgeGameSink` 는 자신의 게임 객체 자체를 모니터로 동기화한다(`OmokGameSink` 와 같은 패턴).
  이 직렬화가 빠진 채로 짠 적이 있었고 실제 레이스로 드러났다.

### 결정론적 재생

기보에는 전체 상태가 아니라 **PRNG 시드, 틱별 입력, 틱별 이탈**만 남긴다. 이탈은 입력 스트림
밖에서 게임 상태를 바꾸는 별개의 사건이다 — 참가자가 방을 나가는 것은 기록된 입력이 아니라
`eliminate(...)` 호출이므로, 시드와 입력만으로는 이탈한 참가자가 계속 살아 움직이는 다른 게임이
재현된다. 장애물피하기는 전원이 이탈해 한 명만 남는 것이 주된 종료 경로이므로, 이 채널이 없으면
재생이 틀린 승자·틀린 길이를 "정상"으로 내놓는다. 재생기는 각 틱을 진행하기 **전에** 그 틱의
이탈을 기록된 순서대로 먼저 반영해야 한다(GAME-AC-19).

기보 헤더는 `"v": 2` 를 싣는다. 이탈이 `moves` 와 나란한 별도 채널로 추가되며 파일의 의미가
바뀌었기 때문이다 — **입력만 있는 줄은 v1과 v2가 바이트 단위로 동일하다.** 그래서 v1 그대로
읽는 리더는 v2 파일도 파싱 에러 없이 읽어 내고, `moves` 만 보고 이탈을 놓친 채 조용히 다른
게임을 "정상"으로 재생해 버린다. 그래서 이 필드가 생긴 시점에 버전을 반드시 올린다 — 읽는 쪽이
몰라도 되는 변경이 아니다.

PRNG 은 **xorshift32** 로 고정한다. 브라우저가 서버와 같은 난수열을 만들어야 하는데
`java.util.Random` 을 쓰면 JS 가 48비트 LCG 를 재구현해야 한다. 시드가 0이면 1로 바꾼다 —
xorshift 는 0에서 멈춘다.

격자 크기·틱 간격·확률 곡선은 기보 헤더에 실어 보낸다(`cols`, `rows`, `tickMs`, `prng`,
`baseSpawn`, `spawnStep`, `spawnStepTicks`, `maxSpawn`). 클라이언트가 같은 규칙으로 재생해야
하므로 이 값들이 곧 계약이다 — 그러므로 **리더는 이 값들을 자기 상수와 대조하고 다르면 즉시
던진다**(`v` 가 다를 때와 같은 태도다). 헤더를 싣기만 하고 읽지 않으면, 서버 상수가 바뀐 뒤에
저장된 옛 기보를 새 상수로 재생할 때 예외도 경고도 없이 다른 게임이 그려진다.

시작 위치도 같은 계약이다. `round((i + 0.5) * COLUMNS / playerCount - 0.5)` 를 `[0, COLUMNS-1]`
로 클램프한 값이고(8인이면 `[0,2,3,5,6,8,9,11]`), 한 칸이라도 다르면 틱 1부터 갈린다.
`DodgeRulesTest.startingCellsForEightPlayersAreExactlyTheseColumns` 가 서버 쪽 골든이다.

`DodgeGame` 이 위치를 담는 자료구조도 이 계약의 일부다 — `positions` 는 `LinkedHashMap` 이고
`frame(...)`(`DodgeGame.java`)이 프레임을 만들 때 그 `LinkedHashMap` 을 복사해 삽입 순서를
그대로 굳힌다. `Map.copyOf`/평범한 `HashMap` 을 썼다면 순회 순서가 실행마다 해시 솔트에 좌우돼
`positions[]` 직렬화 순서가 흔들리는데, 재생 비교는 그 순서까지 본다 — 조용히 다른(하지만
"정상으로 보이는") 기보를 만들어 내고 어떤 테스트도 실패하지 않는다.

## 영속화

- 종료된 게임만 남긴다. `game_results` 1행 + `game_result_participants` N행 (`V2__game.sql`).
- 기보는 MinIO 에 `games/{gameType}/{gameResultId}.ndjson` 으로 종료 시 한 번에 올린다
  (`ReplayUploader`, `Content-Type: application/x-ndjson`).
- 순서는 결과 행 → 참가자 행 → 업로드 → key 부착. 업로드가 실패하면 `replay_object_key` 가
  `null` 로 남을 뿐 전적은 그대로다.
- `GAME_END` 는 이 저장을 기다리지 않고 즉시 나간다 — 승자를 알리는 데 DB/스토리지 왕복을
  강요하지 않기 위해서다. 그래서 `GAME_END` 페이로드에는 `gameResultId` 가 없다. 참가자는
  나중에 `GET /api/game/me/results` 로 결과를 찾는다.
- `app-webflux` 는 `S3AsyncClient` 를 쓴다. `app-mvc` 의 `S3Client` 는 블로킹이라 쓸 수 없다.
- **기보 다시보기 접근 제한은 두 겹이다.** `GameResultController.replay` 가
  `GameResultQueryRepository.findReplayAccess` 로 `game_result_participants.member_id` 조인
  조건을 걸어(`FIND_REPLAY_ACCESS`, GAME-AC-22) 그 회원이 실제 참가자일 때만 600초짜리
  presigned GET URL(`ReplayUploader.presignedDownloadUrl`, `storage.s3.presigned-url-expiration-seconds`)
  을 내준다. 이 참가자 확인은 **버킷 자체가 비공개일 때만** 의미가 있다 — 버킷이 익명
  읽기를 허용하면 URL 을 아는 누구나 presigned 서명 없이도 오브젝트를 내려받을 수 있어
  이 확인이 무력화된다. `.docker-compose/docker-compose.yml` 이 `mc anonymous set none` 으로
  버킷을 비공개로 초기화해 이 조건을 만족시킨다.

## WebSocket 프로토콜

봉투는 방향마다 모양이 다르다. 클라이언트 → 서버는 `{type, seq, payload}`, 서버 → 클라이언트는
`{type, ackSeq?, payload}` 다. `ackSeq` 는 `OMOK_PLACE`/`DODGE_MOVE` 처리 결과인 `OMOK_MOVED`·
`OMOK_REJECTED`·`ERROR` 에만 실린다 — 그 경로만 클라이언트의 `seq` 를 넘겨받기 때문이다.
`JOIN` / `READY` / `START` 가 실패해 나가는 `ERROR` 에는 `ackSeq` 가 없다. `ROOM_STATE`,
`GAME_START` 등 방 전체에 뿌리는 메시지도 특정 명령의 응답이 아니므로 `ackSeq` 가 없다.

- 클라이언트 → 서버: `JOIN` `LEAVE` `READY` `START` `OMOK_PLACE` `DODGE_MOVE`
- 서버 → 클라이언트: `ROOM_STATE` `GAME_START` `GAME_SNAPSHOT` `OMOK_MOVED` `OMOK_REJECTED`
  `DODGE_TICK` `GAME_END` `ERROR`

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
| GAME-AC-10 | 흑의 삼삼·사사·장목 착수는 `OMOK_REJECTED` 로 거절하고 판 상태를 바꾸지 않는다 | `RenjuRuleTest`, `OmokGameTest`, `OmokGameSinkTest` |
| GAME-AC-11 | 백은 금수가 없고 6목 이상으로도 승리한다 | `WinRuleTest`, `OmokGameTest` |
| GAME-AC-12 | 흑은 정확히 5목일 때만 승리한다 | `WinRuleTest` |
| GAME-AC-13 | 열린 삼 판정은 열린 사를 만드는 자리가 금수면 열린 삼으로 보지 않는다 | `RenjuRuleTest` |
| GAME-AC-14 | 차례가 아닌 참가자의 착수와 이미 놓인 자리 착수는 거절한다 | `OmokGameTest`, `OmokGameSinkTest` |
| GAME-AC-15 | 수당 제한시간을 넘기면 그 참가자가 패한다 | `OmokGameTest`(`OmokGame.timeout` 단위 테스트만 있다 — 아무 곳에서도 호출하지 않아 실제로는 강제되지 않는다. 알려진 미비점) |
| GAME-AC-16 | 틱당 입력은 참가자별 1회만 반영하고, 격자 밖 이동은 무시한다 | `DodgeGameTest`, `DodgeGameSinkTest` |
| GAME-AC-17 | 참가자와 장애물이 서로 지나친 경우(스왑)도 충돌로 판정한다 | `DodgeGameTest` |
| GAME-AC-18 | 탈락 역순이 순위이고, 같은 틱 탈락은 공동 순위다 | `DodgeGameTest` |
| GAME-AC-19 | 같은 시드와 같은 입력 로그로 재생하면 원본과 같은 결과가 나온다. 충돌로 끝난 게임과 이탈로 끝난 게임 두 종료 경로 모두 커버한다 — 이탈 경로가 실제로 깨져 있던 경로다 | `DodgeReplayTest`, `Xorshift32Test` |
| GAME-AC-20 | 게임이 끝나면 결과 1행과 참가자 행들을 기록하고 기보를 업로드한다 | `GameResultServiceTest`, `OmokGameSinkTest`, `DodgeGameSinkTest.theGameEndsAndRecordsAResult` |
| GAME-AC-21 | 기보 업로드가 실패해도 결과는 남고 `replay_object_key` 는 `null` 이다 | `GameResultServiceTest`, `ReplayUploaderTest` |
| GAME-AC-22 | 기보 다시보기는 그 게임 참가자 본인에게만 presigned URL을 발급한다. 게스트(`member_id` 없음)는 애초에 `member_id` 로 참가자를 확인하는 이 조회의 대상이 될 수 없으므로 기보를 절대 조회할 수 없다 | `GameResultControllerTest`, `GameResultQueryRepositoryTest`(`FIND_REPLAY_ACCESS` 의 `p.member_id = :memberId` SQL 고정) |
| GAME-AC-23 | 진행 중인 게임에 재접속하면 화면을 다시 그릴 수 있는 `GAME_SNAPSHOT` 을 받는다 — 오목은 지금까지의 착수 목록(`{x, y, color}`, 수마다 색을 실어 헤더 없이 판을 세울 수 있다. 기보 형식과 다른 것은 의도된 것이다)과 `nextTurn`·`turnDeadline`, 장애물피하기는 현재 `tick`·`positions`·`obstacles`. 스냅샷은 게임 상태를 바꾸지 않는다(오목의 차례도, 장애물피하기의 틱 카운터도 그대로다) | `RoomServiceTest.joinReportsWhetherItSeatedANewcomerOrRevivedAnExistingMember`, `RoomCommandDispatcherTest.aReconnectIntoARunningGameAsksTheSinkForASnapshot`·`theSnapshotIsRequestedAfterTheRoomStateBroadcast`, `OmokGameSinkTest.aRejoinBroadcastsEveryMoveSoFarWithTheCurrentTurnAndConsumesNeither`, `DodgeGameSinkTest.aRejoinBroadcastsTheCurrentTickPositionsAndObstaclesWithoutAdvancing`, `DodgeGameTest.currentFrameReportsTheStateWithoutAdvancingTheTick` |
| GAME-AC-24 | 최초 참가, 아직 게임이 시작되지 않은 방으로의 재접속, 그리고 이미 끝났지만 아직 정리되지 않은 게임으로의 재접속은 `GAME_SNAPSHOT` 을 만들지 않는다 | `RoomCommandDispatcherTest.aFirstTimeJoinAsksForNoSnapshot`·`aReconnectIntoARoomWhoseGameHasNotStartedAsksForNoSnapshot`, `OmokGameSinkTest.aRejoinWithNoGameInProgressBroadcastsNothing`·`aRejoinInsideTheWindowWhereTheGameIsFinishedButStillMappedBroadcastsNothing`, `DodgeGameSinkTest.aRejoinWithNoGameInProgressBroadcastsNothing`·`aRejoinIntoAFinishedButNotYetEvictedGameBroadcastsNothing` |
| GAME-AC-25 | `GAME_SNAPSHOT` 은 게임 모니터를 잡은 채로 나가므로, 동시에 도착한 착수나 틱이 스냅샷을 앞질러 방에 도착할 수 없다 — 낡은 전체 상태가 이미 반영된 수/틱을 지우는 일이 없다 | `OmokGameSinkTest.aConcurrentPlacementCannotOvertakeTheSnapshotOnTheWire`, `DodgeGameSinkTest.aConcurrentTickCannotOvertakeTheSnapshotOnTheWire` |
| GAME-AC-26 | 게임 HTTP API 의 실패 응답은 `ApiResponse` 봉투(`header.isSuccessful=false`, `header.resultCode`, `header.message`=코드)로 나가고, 초대 코드 오류·닉네임 중복·정원 초과·진행 중·방 없음·권한 없음이 각자 다른 코드를 싣는다. 코드가 없는 예외는 상태별 폴백 코드로, 예상 못 한 예외는 `500 game_unexpected` 로 뭉개고 예외 메시지를 본문에 흘리지 않는다. `front/lib/errors/error-messages.ts` 와 카탈로그는 **양방향**으로 일치해야 한다(지도에 없는 코드도, 코드 없는 지도 키도 실패) | `GameApiErrorEnvelopeTest`, `GameErrorCodeTest` |
| GAME-AC-27 | 게스트 토큰은 정원이 찬 방과 이미 시작된 방에는 발급되지 않는다(Redis 에 아무것도 쓰지 않는다). 자리가 남은 `WAITING` 방에는 그대로 발급된다. 닉네임 중복 검사가 상태·정원 검사보다 먼저 돌므로, 이미 그 방에 있는 이름에게는 정원·상태 오류가 가지 않는다 | `GuestIdentityServiceTest.refusesATokenForAFullRoom`·`refusesATokenWhenTheGameHasAlreadyStarted`·`stillIssuesForARoomWithSpaceThatHasNotStarted`·`someoneAlreadyInAFullRoomIsToldTheNicknameIsTakenNotThatTheRoomIsFull`, `GameApiErrorEnvelopeTest` |
| GAME-AC-28 | WebSocket `ERROR` 도 HTTP 봉투와 같은 `GameErrorCode` 카탈로그를 쓴다 — `{status, code, message}` 이고 `code` 는 `game_*` 문자열이다. 카탈로그 밖 예외는 `game_unexpected` 로 뭉개고 예외 메시지를 싣지 않는다. 참가가 거절된 세션은 아직 방 허브를 구독하지 않으므로, 닫히기 **전에** 그 프레임을 세션에 직접 받는다 — 토큰 인증 실패와 방의 거절(틀린 초대 코드·정원 초과·이미 시작) 두 갈래 모두. 방의 거절은 방에 브로드캐스트하지 않는다(당사자에게는 닿지 않고 남들만 받는다) | `RoomCommandDispatcherTest.aFailedCommandCarriesTheErrorCodeNotJustAMessage`·`anUnexpectedFailureCarriesTheGenericCodeAndLeaksNothing`·`aGameCommandFromANonMemberCarriesTheNotAMemberCode`·`aRejectedJoinTellsTheCallerWhyAndDoesNotBotherTheRoom`·`anAcceptedJoinReportsNoReason`, `GameWebSocketHandlerTest.aFailedAuthenticationSendsACodedErrorFrameBeforeClosing`·`aJoinRejectedByTheRoomAlsoSendsACodedErrorFrameBeforeClosing` |
| GAME-AC-29 | 시작 최소 인원은 게임 종류별이다 — 장애물피하기는 방장 혼자 READY 상태여도 시작할 수 있고(연습 모드), 오목은 2인 미만이면 거절한다. 프론트의 시작 버튼 활성화 판단도 같은 규칙을 쓴다 | `RoomServiceTest.dodgeStartsWithASingleReadyPlayer`·`omokNeedsTwoReadyMembersToStart`, `front/lib/room-sidebar.test.ts` |

## 비기능 요구사항

- 실시간 통신은 WebSocket([`../_global/adr/ADR-005-realtime-websocket.md`](../_global/adr/ADR-005-realtime-websocket.md)).
- **블로킹 호출 금지.** Redis 는 `ReactiveStringRedisTemplate`, DB 는 R2DBC.
- 게임 상태는 단일 인스턴스 인메모리다. 수평 확장은 ADR-005 의 후속 과제다.
