-- 댓글 수정. 고친 시각을 따로 들어 화면이 "수정됨" 을 붙일 수 있게 한다.
--
-- NULL 은 "한 번도 고치지 않았다" 이다. created_at 과 같은 값으로 채우지 않는 이유가 있다:
-- 그러면 갓 쓴 댓글과 쓰자마자 고친 댓글을 구별할 수 없다. 기존 행은 전부 NULL 로 남는다.
ALTER TABLE pokemon_order_comments ADD COLUMN updated_at TIMESTAMP(6);
