-- 무소속 할 일: 소유권을 프로젝트 경유가 아니라 할 일 자신의 member_id 로 판별한다.
ALTER TABLE tasks ADD COLUMN member_id BIGINT;
UPDATE tasks SET member_id = (SELECT p.member_id FROM projects p WHERE p.id = tasks.project_id);
ALTER TABLE tasks ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE tasks ALTER COLUMN project_id DROP NOT NULL;
CREATE INDEX idx_tasks_member_id ON tasks (member_id);
