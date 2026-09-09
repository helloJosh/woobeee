-- 글 설명(한 줄 요약) — 제목처럼 언어별 (BLOG-AC-23). 비어 있으면 화면은 본문 요약으로 대체한다.
ALTER TABLE posts ADD COLUMN description_ko VARCHAR(300), ADD COLUMN description_en VARCHAR(300);
