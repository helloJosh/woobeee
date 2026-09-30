-- 상품표를 차수마다 갖게 한다.
--
-- 패스는 달마다 바뀐다("GO패스 디럭스: 9월" → "10월"). 주최자 상품표 하나를 여러 차수가
-- 함께 보면, 9월 패스를 10월로 고치는 순간 이미 닫힌 1차 화면에도 10월이 뜬다. 지나간
-- 신청서의 금액은 항목 스냅샷 덕에 안 흔들리지만 "무엇을 팔았는가" 가 사후에 바뀌는 셈이다.
--
-- 그래서 상품 행에 round_id 를 둔다:
--   round_id IS NULL  → 주최자의 틀(template). 차수를 열 때 이것을 복사한다
--   round_id = N      → 그 차수의 상품표. 그 차수에서만 보이고 고쳐진다
ALTER TABLE pokemon_products ADD COLUMN round_id BIGINT REFERENCES pokemon_rounds (id);

-- (주최자, 이름, 통화) 유일성은 틀 안에서만 성립한다. 차수마다 같은 이름이 복사되므로
-- 차수까지 묶어야 한다. NULL 은 유일 제약에서 서로 다르게 취급되므로 부분 인덱스로 나눈다.
ALTER TABLE pokemon_products DROP CONSTRAINT pokemon_products_host_name_currency_key;

CREATE UNIQUE INDEX pokemon_products_template_key
    ON pokemon_products (host_member_id, name, currency)
    WHERE round_id IS NULL;

CREATE UNIQUE INDEX pokemon_products_round_key
    ON pokemon_products (round_id, name, currency)
    WHERE round_id IS NOT NULL;

CREATE INDEX idx_pokemon_products_round ON pokemon_products (round_id, active, sort_order);

-- 이미 열린 차수에는 상품표가 없다. 그 차수 통화에 맞는 주최자 상품을 복사해 심어 준다 —
-- 없으면 신청 화면에 고를 것이 하나도 뜨지 않는다.
INSERT INTO pokemon_products (host_member_id, round_id, name, currency, price, coins,
                              sort_order, active, first_seen_at, last_seen_at)
SELECT p.host_member_id, r.id, p.name, p.currency, p.price, p.coins,
       p.sort_order, p.active, now(), now()
  FROM pokemon_rounds r
  JOIN pokemon_products p
    ON p.host_member_id = r.host_member_id
   AND p.currency = r.currency
   AND p.round_id IS NULL;
