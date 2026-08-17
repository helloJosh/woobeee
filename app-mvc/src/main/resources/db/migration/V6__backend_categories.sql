-- BACKEND 하위 분류를 Spring / Database / Kafka / Redis 로 맞춘다.
--
-- 왜 'Spring Batch' 를 지우고 'Spring' 을 새로 만들지 않는가: 글 3편이 posts.category_id = 2
-- 를 가리킨다. 지웠다 다시 만들면 새 id 가 붙고 그 3편이 분류를 잃는다. 그래서 행은 그대로
-- 두고 이름만 바꾼다.
--
-- 왜 V5 를 고치지 않는가: 이미 적용된 DB 가 있어서 내용을 바꾸면 Flyway 가 체크섬 불일치로
-- 부팅을 막는다. V5 가 V3 를 덮어쓴 것과 같은 이유다.
--
-- 이름 조건을 함께 거는 것은 여러 번 적용해도 같은 상태가 되게 하려는 것이다. 두 번째
-- 실행부터는 이미 'Spring' 이라 아무 행도 건드리지 않는다.
UPDATE categories
SET name_ko    = 'Spring',
    name_en    = 'Spring',
    updated_at = TIMESTAMP '2026-08-17 00:00:00'
WHERE id = 2
  AND name_ko = 'Spring Batch';

-- Redis 는 id 를 박지 않는다. V5 가 id 를 명시한 이유는 posts 가 그 값을 들고 있어서였는데,
-- 새로 만드는 이 분류에는 참조하는 글이 없다. 대신 이름+부모로 중복을 막는다 --
-- categories 에는 name_ko 유니크 제약이 없어 ON CONFLICT 를 쓸 수 없다.
INSERT INTO categories (name_ko, name_en, created_at, updated_at, parent_id)
SELECT 'Redis', 'Redis',
       TIMESTAMP '2026-08-17 00:00:00', TIMESTAMP '2026-08-17 00:00:00', 1
WHERE NOT EXISTS (
    SELECT 1 FROM categories WHERE name_ko = 'Redis' AND parent_id = 1
);
