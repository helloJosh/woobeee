-- 패스·티켓은 App Store 가 아니라 손으로 관리한다.
--
-- App Store 의 In-App Purchases 목록은 패스를 'Event Pass Deluxe' 같은 자리표시 이름으로만
-- 보여 준다. 인게임 상점에는 'GO패스 디럭스: 9월' 처럼 실제 이름이 뜨고 달마다 바뀐다 —
-- 스토어 페이지에서는 그 이름을 알 수 없다. 그래서 스크레이핑을 걷어내고 운영자가 화면에서
-- 직접 넣고 고치는 쪽으로 바꾼다.

-- 주문에 쓰인 적이 있으면 지우지 않고 내린다 — 과거 신청서가 가리키고 있다.
UPDATE pokemon_products SET active = false
WHERE name IN ('Event Ticket', 'Event Ticket Giftable', 'Event Pass 3 Deluxe', 'Event Pass Deluxe')
  AND id IN (SELECT product_id FROM pokemon_order_items WHERE product_id IS NOT NULL);

DELETE FROM pokemon_products
WHERE name IN ('Event Ticket', 'Event Ticket Giftable', 'Event Pass 3 Deluxe', 'Event Pass Deluxe')
  AND id NOT IN (SELECT product_id FROM pokemon_order_items WHERE product_id IS NOT NULL);

-- 2026-09-29 인게임 상점에서 읽은 값. 앞으로는 /pokemon/products 에서 운영자가 고친다.
INSERT INTO pokemon_products (name, price_inr, coins, sort_order, first_seen_at, last_seen_at)
VALUES ('GO패스 디럭스: 9월',                      229.00, 0,  7, now(), now()),
       ('GO패스 디럭스: 10월',                     229.00, 0,  8, now(), now()),
       ('GO패스 디럭스: 수확 축제',                 149.00, 0,  9, now(), now()),
       ('Pokémon GO 와일드 에리어 2026 글로벌 티켓', 349.00, 0, 10, now(), now())
ON CONFLICT (name) DO UPDATE
    SET price_inr = EXCLUDED.price_inr,
        coins      = EXCLUDED.coins,
        sort_order = EXCLUDED.sort_order,
        active     = true;
