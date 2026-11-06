-- 환차손익을 없앤다. 원화는 저장하지 않고 하루 한 번 갱신되는 환율로 매번 계산한 "예상 금액"이다.
-- 차수: 환율 방식(FIXED/PER_ORDER)·고정 환율·결제 환율을 모두 지운다.
-- 신청서: 신청 당시 환율과 확정 원화(items_krw, transfer_krw)를 지운다. donation_krw 는 신청자가
-- 직접 적는 값이라 남긴다. 컬럼에 걸린 CHECK 제약은 컬럼과 함께 사라진다.
ALTER TABLE pokemon_rounds
    DROP COLUMN rate_mode,
    DROP COLUMN quoted_rate,
    DROP COLUMN quoted_at,
    DROP COLUMN settled_rate,
    DROP COLUMN settled_at;

ALTER TABLE pokemon_orders
    DROP COLUMN quoted_rate,
    DROP COLUMN quoted_at,
    DROP COLUMN items_krw,
    DROP COLUMN transfer_krw;
