-- 주최자의 기본 입금 계좌. 차수를 열 때마다 다시 치지 않게 한 번 적어 두고 자동으로 채운다.
--
-- 차수는 계속 자기 계좌를 따로 들고 있다(pokemon_rounds.bank_account). 여기 기본값을 고쳐도
-- 이미 연 차수의 계좌가 바뀌면 안 되기 때문이다 — 그 계좌로 이미 입금한 사람이 있다.
ALTER TABLE pokemon_hosts ADD COLUMN bank_account VARCHAR(200);
