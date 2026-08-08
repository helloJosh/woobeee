-- V3 가 심은 자리표시 카테고리(백엔드/프론트엔드/인프라/알고리즘/회고/기타)를 실제 운영
-- 분류로 교체한다. 구 프로젝트(woobeee-blog)에서 글과 함께 옮겨온 그 분류다.
--
-- 왜 V3 를 고치지 않고 새 파일인가: V3 는 이미 적용된 DB 가 있어서 내용을 바꾸면 Flyway 가
-- 체크섬 불일치로 부팅을 막는다.
--
-- 왜 id 를 명시하는가: posts.category_id 가 2·5·6 을 가리킨다(각각 Spring Batch·Database·
-- Kafka). 이름만 갈아끼우면 글이 엉뚱한 분류에 붙는다. 새 DB 에서는 V3 가 방금 1~6 을
-- 채웠으므로 아래 upsert 가 그 여섯 행을 제자리에서 고쳐 쓴다.

INSERT INTO categories (id, name_ko, name_en, created_at, updated_at, parent_id) VALUES
    (1, 'BACKEND',      'BACKEND',      TIMESTAMP '2025-09-01 23:04:23.024768', TIMESTAMP '2025-09-01 23:04:23.024820', NULL),
    (3, 'FRONTEND',     'FRONTEND',     TIMESTAMP '2025-09-01 23:04:23.058000', TIMESTAMP '2025-09-01 23:04:23.058598', NULL),
    (2, 'Spring Batch', 'Spring Batch', TIMESTAMP '2025-09-01 23:04:23.053480', TIMESTAMP '2025-09-01 23:04:23.053494', 1),
    (5, 'Database',     'Database',     TIMESTAMP '2025-09-03 23:04:23.058000', TIMESTAMP '2025-09-03 23:04:23.058000', 1),
    (6, 'Kafka',        'Kafka',        TIMESTAMP '2026-02-08 17:25:13.000000', TIMESTAMP '2026-02-08 17:25:35.000000', 1),
    (4, 'NextJS',       'NextJS',       TIMESTAMP '2025-09-01 23:04:23.064046', TIMESTAMP '2025-09-01 23:04:23.064058', 3)
ON CONFLICT (id) DO UPDATE SET
    name_ko    = EXCLUDED.name_ko,
    name_en    = EXCLUDED.name_en,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    parent_id  = EXCLUDED.parent_id;

-- V3 가 1~6 이 아닌 id 로 심은 자리표시가 남아 있을 수 있다(그 DB 에 이미 카테고리가 있어
-- V3 의 WHERE NOT EXISTS 가드가 일부만 통과한 경우). 아무도 참조하지 않을 때만 지운다.
DELETE FROM categories c
WHERE c.name_ko IN ('백엔드', '프론트엔드', '인프라', '알고리즘', '회고', '기타')
  AND NOT EXISTS (SELECT 1 FROM posts p WHERE p.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM categories child WHERE child.parent_id = c.id);

-- id 를 직접 박았으므로 IDENTITY 시퀀스를 최댓값 뒤로 밀어 둔다.
SELECT setval(pg_get_serial_sequence('categories', 'id'), (SELECT MAX(id) FROM categories));
