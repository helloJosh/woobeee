#!/usr/bin/env bash
#
# app-mvc / front 이미지를 멀티플랫폼(linux/amd64 + linux/arm64)으로 빌드한다.
#
#   scripts/docker-build.sh [app-mvc|front|all]      # 기본 all
#
# 출력 모드 — 멀티플랫폼 이미지는 로컬 `docker images` 에 담을 수 없어서 목적지를 골라야 한다:
#
#   (기본)         이 기계의 아키텍처 하나만 빌드해 로컬 docker 에 넣는다 (로컬 실행·테스트용)
#   PUSH=1         amd64+arm64 를 모두 빌드해 레지스트리로 푸시한다  ← 서버가 어떤 CPU 든 pull 하면 된다
#   OCI_OUT=dist   amd64+arm64 를 모두 빌드해 dist/<이름>.tar(OCI) 로 저장한다
#                  → k3s: `sudo k3s ctr images import dist/app-mvc.tar`
#
# 환경변수:
#   REGISTRY   이미지 이름 앞에 붙일 레지스트리/계정. 예) ghcr.io/helloJosh  (PUSH=1 이면 필수)
#   TAG        태그. 기본 latest
#   PLATFORMS  기본 linux/amd64,linux/arm64
#   MVC_ORIGIN / NEXT_PUBLIC_GOOGLE_CLIENT_ID
#              front 의 "빌드 시점" 값(front/Dockerfile 참고). MVC_ORIGIN 기본값은 k8s/compose 서비스
#              이름인 http://app-mvc:8000 이다 — 로컬 개발 기본값(localhost:8000)이 아니다.
#
# 예)
#   scripts/docker-build.sh                                   # 로컬 테스트용(현재 CPU 하나)
#   REGISTRY=ghcr.io/helloJosh PUSH=1 scripts/docker-build.sh
#   OCI_OUT=dist scripts/docker-build.sh app-mvc
set -euo pipefail

cd "$(dirname "$0")/.."

TARGET="${1:-all}"
TAG="${TAG:-latest}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER="woobeee-multi"
PREFIX="${REGISTRY:+${REGISTRY%/}/}woobeee"

MVC_ORIGIN="${MVC_ORIGIN:-http://app-mvc:8000}"
NEXT_PUBLIC_GOOGLE_CLIENT_ID="${NEXT_PUBLIC_GOOGLE_CLIENT_ID:-}"

case "$TARGET" in app-mvc|front|all) ;; *) echo "usage: $0 [app-mvc|front|all]" >&2; exit 2 ;; esac

if [[ "${PUSH:-}" == 1 && -z "${REGISTRY:-}" ]]; then
  echo "PUSH=1 에는 REGISTRY 가 필요하다. 예) REGISTRY=ghcr.io/helloJosh PUSH=1 $0" >&2
  exit 2
fi

# 기본 docker 드라이버는 멀티플랫폼 빌드를 못 한다 → docker-container 드라이버 빌더를 한 번 만든다.
if ! docker buildx inspect "$BUILDER" >/dev/null 2>&1; then
  echo ">> buildx 빌더 '$BUILDER' 생성 (docker-container 드라이버, 한 번만)"
  docker buildx create --name "$BUILDER" --driver docker-container --bootstrap >/dev/null
fi

# 출력 모드 → buildx 인자. 이름($1)을 받아 해당 이미지의 인자를 만든다.
output_args() {
  local name="$1"
  if [[ "${PUSH:-}" == 1 ]]; then
    echo "--platform $PLATFORMS --push"
  elif [[ -n "${OCI_OUT:-}" ]]; then
    mkdir -p "$OCI_OUT"
    echo "--platform $PLATFORMS --output type=oci,dest=$OCI_OUT/$name.tar"
  else
    # 로컬 모드: 이 기계의 아키텍처 하나만. --platform 을 주지 않으면 buildx 가 호스트 것을 쓴다.
    echo "--load"
  fi
}

build_app_mvc() {
  echo ">> app-mvc → $PREFIX/app-mvc:$TAG"
  # shellcheck disable=SC2046
  docker buildx build --builder "$BUILDER" $(output_args app-mvc) \
    -f app-mvc/Dockerfile -t "$PREFIX/app-mvc:$TAG" .
}

build_front() {
  echo ">> front (MVC_ORIGIN=$MVC_ORIGIN) → $PREFIX/front:$TAG"
  # shellcheck disable=SC2046
  docker buildx build --builder "$BUILDER" $(output_args front) \
    --build-arg "MVC_ORIGIN=$MVC_ORIGIN" \
    --build-arg "NEXT_PUBLIC_GOOGLE_CLIENT_ID=$NEXT_PUBLIC_GOOGLE_CLIENT_ID" \
    -t "$PREFIX/front:$TAG" front
}

[[ "$TARGET" == app-mvc || "$TARGET" == all ]] && build_app_mvc
[[ "$TARGET" == front   || "$TARGET" == all ]] && build_front
echo ">> 완료"
