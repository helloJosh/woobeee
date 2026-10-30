#!/usr/bin/env bash
#
# k8s 중단. 워크로드만 지우고 PVC(DB·Redis·MinIO 데이터)는 남긴다.
# 데이터까지 지우려면: kubectl delete namespace woobeee  (되돌릴 수 없다)
set -euo pipefail
K8S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# namespace 를 지우면 PVC 도 같이 사라지므로 namespace 는 빼고 지운다.
kubectl delete -n woobeee deploy/front deploy/app-mvc statefulset/minio statefulset/redis statefulset/postgres \
  job/createbuckets --ignore-not-found
kubectl delete svc -n woobeee --all
