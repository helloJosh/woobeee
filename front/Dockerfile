# syntax=docker/dockerfile:1
#
# front (Next.js 14) 이미지.
# 빌드 컨텍스트는 front/ 디렉터리다(백엔드 모듈이 필요 없다):
#
#   docker build -t woobeee/front:latest \
#     --build-arg MVC_ORIGIN=http://app-mvc:8000 \
#     front
#
#   docker run --rm -p 3000:3000 woobeee/front:latest
#
# 멀티플랫폼(amd64+arm64) 이미지는 scripts/docker-build.sh 로 만든다.
#
# [중요] 아래 ARG 들은 "빌드 시점"에 값이 굳는다. 런타임 -e 로는 바꿀 수 없다.
#   - MVC_ORIGIN                  : next.config.mjs 의 rewrites 목적지가 빌드 때 라우트 매니페스트에 박힌다.
#   - NEXT_PUBLIC_*               : 브라우저 번들에 문자열로 인라인된다.
# 환경(dev/prod)이 다르면 그 환경 값으로 이미지를 다시 빌드해야 한다.

ARG NODE_VERSION=20

# =====================================================================
# 1단계 deps: 의존성 설치만. package*.json 이 안 바뀌면 이 레이어는 캐시된다.
# =====================================================================
# --platform=$BUILDPLATFORM: deps/build 단계는 빌드 기계의 아키텍처로 네이티브 실행한다.
# next build 의 결과(.next)는 아키텍처와 무관한 JS 번들이라 한 번만 만들면 된다.
FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# npm ci: lock 파일 그대로 재현 가능하게 설치한다(npm install 은 lock 을 바꿀 수 있다).
# 캐시 마운트로 ~/.npm 다운로드 캐시를 빌드 사이에 재사용한다.
RUN --mount=type=cache,target=/root/.npm npm ci

# =====================================================================
# 2단계 prod-deps: 실행에 필요한 의존성만(devDependencies 제외).
#   next start 는 런타임에 next 패키지가 필요하므로 node_modules 를 최종 이미지에 가져간다.
#   [멀티플랫폼] 이 단계만 "타깃" 아키텍처로 돈다 — next 의 SWC 바이너리 같은 optional 네이티브
#   의존성이 아키텍처별로 달라서, 빌드 기계의 node_modules 를 가져가면 다른 아키텍처에서 못 뜬다.
# =====================================================================
FROM node:${NODE_VERSION}-alpine AS prod-deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev

# =====================================================================
# 3단계 build: next build. 결과물은 .next/ 에 생긴다.
# =====================================================================
FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

# 빌드 시점에 굳는 값들 (위 [중요] 참고). 기본값은 로컬 개발용이다.
ARG MVC_ORIGIN=http://localhost:8000
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=
ENV MVC_ORIGIN=$MVC_ORIGIN \
    NEXT_PUBLIC_GOOGLE_CLIENT_ID=$NEXT_PUBLIC_GOOGLE_CLIENT_ID \
    NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY . .
# 주의: next.config.mjs 가 typescript.ignoreBuildErrors 를 켜 둬서 이 빌드는 타입 검사를 하지 않는다.
# 타입/테스트는 이미지 밖에서 `npm test` 로 돌린다.
RUN npm run build

# =====================================================================
# 4단계 runtime: 실행에 필요한 것만 담은 최종 이미지.
# =====================================================================
FROM node:${NODE_VERSION}-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000

# 자주 바뀌는 것을 뒤에 둔다: 의존성(node_modules) → 설정/정적 파일 → 빌드 결과(.next).
# node:alpine 이미지에는 비root 사용자 'node'(uid 1000)가 이미 있어서 --chown 으로 그 사용자에게 준다.
COPY --from=prod-deps --chown=node:node /app/node_modules ./node_modules
COPY --chown=node:node package.json next.config.mjs ./
COPY --from=build --chown=node:node /app/public ./public
COPY --from=build --chown=node:node /app/.next ./.next

# [보안] root 로 실행하지 않는다.
USER node
EXPOSE 3000

# npm 을 거치지 않고 next 를 직접 실행한다 — npm 이 PID 1 이면 SIGTERM 이 자식(next)에 전달되지 않을 수 있다.
CMD ["node_modules/.bin/next", "start"]
