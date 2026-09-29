-- 상품표에 없는 것을 신청할 수 있게 하는 자유 입력 루피.
-- 상품 합계와 별도로 들고 있다가 total_inr 에 더해진다 — 항목 단가 스냅샷과 섞으면
-- "무엇을 얼마에 샀는가" 가 흐려지기 때문이다.
ALTER TABLE pokemon_orders
    ADD COLUMN extra_inr NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (extra_inr >= 0);
