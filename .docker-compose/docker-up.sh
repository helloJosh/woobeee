#!/usr/bin/env bash
#
# 운영 기동(Docker 컨테이너): 인프라(postgres·redis·minio)와 app-mvc·front 이미지를 빌드해 띄운다.
#
# 비밀값은 이 파일에 적지 않는다. 실행하는 셸에서 먼저 export 한다(없으면 로컬 기본값):
#
#   export GOOGLE_CLIENT_SECRET='...'
#   export GOOGLE_REDIRECT_URI='https://www.woobeee.com/auth/google/callback'
#   export S3_PUBLIC_ENDPOINT='https://image.woobeee.com'
#   .docker-compose/docker-up.sh
#
# AI 요청하기(ai-worker)는 여기서 올리지 않는다 — .env 에 AI_WORKER_SECRET·CLAUDE_CODE_OAUTH_TOKEN 을 넣은 뒤
#   docker compose -f .docker-compose/docker-compose.yml up -d --build ai-worker
#
# 주의
#  - app-mvc 컨테이너는 실제 `market` DB 에 붙고 부팅할 때 Flyway 가 미적용 마이그레이션을 돌린다.
#  - :3000 · :8000 을 로컬 프로세스(scripts/dev-up.sh)가 쥐고 있으면 포트 충돌이 난다. 먼저 내린다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 서비스 이름을 주면 depends_on 으로 postgres·redis·minio 도 함께 뜬다.
docker compose -f .docker-compose/docker-compose.yml up -d --build app-mvc front

docker compose -f .docker-compose/docker-compose.yml ps app-mvc front
echo "  http://localhost:3000   (로그: docker logs -f woobeee-app-mvc)"
