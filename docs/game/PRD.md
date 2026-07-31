# game 도메인 PRD

- 상태: **미작성** — 기능 설계는 후속 spec에서 다룬다.

## 현재 구현된 범위 (골격만)

| 항목 | 내용 |
| --- | --- |
| 스택 | Spring WebFlux + R2DBC (Netty), 포트 8001 |
| 인증 | 공유 Redis 토큰 검증만 수행. 발급은 app-mvc(auth)가 담당 |
| 엔드포인트 | `GET /api/game/health` (공개), `GET /api/game/me` (인증 필요) |
| 스키마 | 없음. 게임 테이블은 `V2__game.sql` 로 추가 예정 |

## 후속 spec에서 결정할 것

- 실시간 통신 방식: WebSocket / SSE / 폴링
- 게임 규칙과 도메인 모델
- 게임 테이블 스키마 (`app-mvc/src/main/resources/db/migration/V2__game.sql`)
- 인수 기준 (Acceptance Criteria) 표

## 인수 기준 (Acceptance Criteria)

미작성 — 후속 spec에서 추가한다. (`CLAUDE.md`의 테스트 결정 프로세스에 따라 AC가 테스트의 단일 출처다.)
