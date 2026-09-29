-- 진행 단계를 실제 운영 순서에 맞춘다: 주문 -> 준비중 -> 입금확인 -> 배달 완료.
-- 준비중(= 인도 스토어에서 실제로 결제하는 단계)이 입금확인보다 앞에 온다 — 돈을 받기 전에
-- 먼저 사 두기 때문이다. 그래서 환차손익도 준비중으로 넘어갈 때 확정된다.
ALTER TABLE pokemon_orders DROP CONSTRAINT pokemon_orders_status_check;

UPDATE pokemon_orders SET status = CASE status
    WHEN 'REQUESTED' THEN 'ORDERED'
    WHEN 'DEPOSITED'  THEN 'DEPOSIT_CONFIRMED'
    -- 옛 SHIPPING 은 입금까지 끝난 뒤의 단계였다. 새 흐름에서 그에 해당하는 것은 입금확인이다.
    WHEN 'SHIPPING'   THEN 'DEPOSIT_CONFIRMED'
    ELSE status
END;

ALTER TABLE pokemon_orders ALTER COLUMN status SET DEFAULT 'ORDERED';
ALTER TABLE pokemon_orders ADD CONSTRAINT pokemon_orders_status_check
    CHECK (status IN ('ORDERED', 'PREPARING', 'DEPOSIT_CONFIRMED', 'DELIVERED', 'CANCELLED'));
