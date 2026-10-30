-- game 도메인(app-webflux)을 제거하면서 전적 테이블과 게임 머니 컬럼도 함께 지운다.
-- 되돌릴 수 없다: 기존 게임 전적/참가자 행과 members.game_money 가 영구 삭제된다
-- (game_money 는 항상 0 이었고 증감 경로가 없었다).
DROP TABLE IF EXISTS game_result_participants;
DROP TABLE IF EXISTS game_results;
ALTER TABLE members DROP COLUMN IF EXISTS game_money;
