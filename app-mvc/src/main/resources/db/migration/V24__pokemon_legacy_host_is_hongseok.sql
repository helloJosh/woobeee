-- 차수가 생기기 전의 공동구매는 실제로 최홍석이 굴렸다. V21 은 그것을 알 수 없어 운영자(1)
-- 앞으로 만들어 두었으므로 여기서 넘긴다. 주소도 자동 생성한 host-1 대신 hs 로 바꾼다.
--
-- 회원 id 는 환경마다 다를 수 있어 이메일로 찾는다. 그 회원이 없으면 아무것도 하지 않는다.
DO $$
DECLARE
    target BIGINT;
    legacy BIGINT;
BEGIN
    SELECT id INTO target FROM members WHERE email = 'chsfree0704@gmail.com';
    SELECT member_id INTO legacy FROM pokemon_hosts WHERE handle = 'host-1';

    IF target IS NULL OR legacy IS NULL THEN
        RETURN;
    END IF;

    -- 넘겨받을 사람이 이미 자기 주소를 갖고 있으면 그 주소를 쓰고, 자동 생성분은 지운다.
    IF EXISTS (SELECT 1 FROM pokemon_hosts WHERE member_id = target) THEN
        UPDATE pokemon_rounds   SET host_member_id = target WHERE host_member_id = legacy;
        UPDATE pokemon_products SET host_member_id = target WHERE host_member_id = legacy;
        DELETE FROM pokemon_hosts WHERE handle = 'host-1';
        RETURN;
    END IF;

    -- 주최자 행은 member_id 가 기본키라 옮기지 않고, 새로 만들고 옛것을 지운다.
    INSERT INTO pokemon_hosts (member_id, handle, bank_account, created_at)
    SELECT target, 'hs', bank_account, created_at FROM pokemon_hosts WHERE handle = 'host-1';

    UPDATE pokemon_rounds   SET host_member_id = target WHERE host_member_id = legacy;
    UPDATE pokemon_products SET host_member_id = target WHERE host_member_id = legacy;

    DELETE FROM pokemon_hosts WHERE handle = 'host-1';
END $$;
