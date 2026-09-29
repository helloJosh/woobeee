-- 상품표가 App Store 에서 오면서 포켓코인이 아닌 상품(Event Ticket, Event Pass …)도 신청할 수
-- 있게 됐다. V15 의 `coins > 0` 은 코인 팩만 있던 시절의 제약이라 그런 항목을 막는다.
ALTER TABLE pokemon_order_items DROP CONSTRAINT pokemon_order_items_coins_check;
ALTER TABLE pokemon_order_items ADD CONSTRAINT pokemon_order_items_coins_check CHECK (coins >= 0);
