#!/usr/bin/env bash
#
# 운영 종료(Docker 컨테이너): app-mvc·front 컨테이너만 멈추고 지운다.
# 인프라(postgres·redis·minio 등)는 그대로 둔다 — 데이터가 든 DB 를 함께 내릴 이유가 없다.
# 이미지와 볼륨은 지우지 않는다.
#
#   .docker-compose/docker-down.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

docker compose -f .docker-compose/docker-compose.yml rm -sf app-mvc front
