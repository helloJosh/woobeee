# syntax=docker/dockerfile:1
#
# ai-worker — Kafka ai.request.v1 을 한 번에 하나씩 소비해 `claude -p`(MCP 서버 woobeee)로 상품·일정을
# 제안하는 워커(ADR-005). 빌드 컨텍스트는 이 디렉터리다:
#
#   docker build -t woobeee/ai-worker:latest ai-worker/
#
# 비밀은 이미지에 넣지 않는다 — CLAUDE_CODE_OAUTH_TOKEN(구독 로그인 토큰. `claude setup-token` 으로 만든다)과
# AI_WORKER_SECRET 은 실행할 때 환경변수로 준다(.docker-compose/.env). 필요한 변수 목록은 src/config.ts.

# =====================================================================
# 1단계 build: 개발 의존성까지 설치해 TypeScript 를 컴파일한다. 이 단계는 최종 이미지에 들어가지 않는다.
# =====================================================================
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# =====================================================================
# 2단계 runtime: 실행에 필요한 것만 — 운영 의존성, 컴파일 결과, claude CLI.
# =====================================================================
FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production

COPY package.json package-lock.json .npmrc ./
RUN npm ci --omit=dev && npm cache clean --force
# claude CLI. 워커가 `claude -p` 로 부른다.
RUN npm install -g @anthropic-ai/claude-code && npm cache clean --force
COPY --from=build /app/dist ./dist

# 루트 파일시스템을 읽기 전용으로 돌려도(compose 의 read_only) 되도록 쓸 곳은 /tmp(tmpfs) 하나뿐이다.
# claude 는 HOME 아래에 설정·세션을 쓰므로 HOME 을 /tmp 안으로 둔다.
ENV HOME=/tmp/worker-home
USER node

# 부팅할 때 HOME 을 만든다(tmpfs 라 매번 비어 있다).
CMD ["sh", "-c", "mkdir -p \"$HOME\" && exec node dist/main.js"]
