#!/usr/bin/env bash
#
# k8s 기동. docker-up.sh 의 쿠버네티스 판이다.
#
#   export GOOGLE_CLIENT_SECRET='...'
#   export GOOGLE_REDIRECT_URI='https://www.woobeee.com/auth/google/callback'
#   export S3_PUBLIC_ENDPOINT='https://image.woobeee.com'
#   .k8s/k8s-up.sh
#
# 주의
#  - 이미지는 레지스트리에서 받지 않는다. 먼저 로컬에서 빌드해 둔다
#    (docker compose -f .docker-compose/docker-compose.yml build app-mvc front).
#    kind / minikube 는 노드로 이미지를 밀어 넣어야 한다: `kind load docker-image woobeee/app-mvc:latest woobeee/front:latest`
#  - app-mvc 파드는 postgres 의 `market` DB 에 붙고 부팅할 때 Flyway 가 미적용 마이그레이션을 돌린다.
#    PVC 가 새것이면 빈 DB 에 전체 스키마가 만들어진다.
set -euo pipefail

K8S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

kubectl apply -k "$K8S"

# 셸 값이 있으면 기본값을 덮는다(파일에 비밀을 적지 않는다).
if [[ -n "${GOOGLE_CLIENT_SECRET:-}" ]]; then
  kubectl -n woobeee patch secret woobeee-secrets --type merge \
    -p "{\"stringData\":{\"GOOGLE_CLIENT_SECRET\":\"${GOOGLE_CLIENT_SECRET}\"}}"
fi
for key in GOOGLE_REDIRECT_URI S3_PUBLIC_ENDPOINT; do
  if [[ -n "${!key:-}" ]]; then
    kubectl -n woobeee patch configmap woobeee-config --type merge -p "{\"data\":{\"$key\":\"${!key}\"}}"
  fi
done
# 값이 바뀌었을 수 있으니 app-mvc 를 다시 띄운다.
kubectl -n woobeee rollout restart deployment/app-mvc

kubectl -n woobeee get pods
echo "  접속: kubectl -n woobeee port-forward svc/front 3000:3000 svc/app-mvc 8000:8000 svc/minio 9000:9000"
echo "  로그: kubectl -n woobeee logs -f deploy/app-mvc"
