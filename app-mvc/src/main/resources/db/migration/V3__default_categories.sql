-- 기본 카테고리 시드. 새로 만든 DB 라도 블로그 화면이 빈 카테고리로 시작하지 않게 한다.
-- Flyway 는 이 파일을 한 번만 적용하지만, 손으로 같은 이름을 이미 만들어 둔 로컬 DB 에서도
-- 안전하도록 이름(name_ko) 기준 WHERE NOT EXISTS 가드를 행마다 건다.
-- 검증: app-mvc 의 DefaultCategoriesSeedTest (실 Postgres 필요).

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '백엔드', 'Backend', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '백엔드');

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '프론트엔드', 'Frontend', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '프론트엔드');

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '인프라', 'Infrastructure', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '인프라');

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '알고리즘', 'Algorithms', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '알고리즘');

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '회고', 'Retrospective', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '회고');

INSERT INTO categories (name_ko, name_en, created_at, updated_at)
SELECT '기타', 'Etc', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name_ko = '기타');
