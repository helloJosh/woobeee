-- BACKEND 하위에 Java 를 추가한다.
--
-- 왜 V6 에 한 줄 얹지 않고 새 파일인가: V6 는 이미 적용된 DB 가 있어서 내용을 바꾸면 Flyway 가
-- 체크섬 불일치로 부팅을 막는다. V6 가 V5 를, V5 가 V3 를 덮은 것과 같은 이유다.
--
-- Redis 와 같은 방식이다 -- 참조하는 글이 없으므로 id 를 박지 않고, categories 에 name_ko
-- 유니크 제약이 없어 ON CONFLICT 대신 이름+부모 NOT EXISTS 로 중복을 막는다. 여러 번
-- 적용해도 같은 상태가 된다.
INSERT INTO categories (name_ko, name_en, created_at, updated_at, parent_id)
SELECT 'Java', 'Java',
       TIMESTAMP '2026-08-18 00:00:00', TIMESTAMP '2026-08-18 00:00:00', 1
WHERE NOT EXISTS (
    SELECT 1 FROM categories WHERE name_ko = 'Java' AND parent_id = 1
);
