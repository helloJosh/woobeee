#!/usr/bin/env bash
#
# scripts/dev-up.sh 로 띄운 서버 셋을 내린다.
#
# PID 파일이 아니라 포트로 찾는다. `npm run dev` 의 $! 는 npm 래퍼 PID 이고 실제 서버는
# 그 자식인 next-server 라, 래퍼만 죽이면 자식이 3000 을 계속 물고 남는다.

set -uo pipefail

PORTS="3000,8000,8001"

pids="$(lsof -ti:$PORTS 2>/dev/null)"
if [ -z "$pids" ]; then
    echo "떠 있는 것이 없다 (:${PORTS//,/ :})"
    exit 0
fi

echo "내린다: $(echo "$pids" | tr '\n' ' ')"
echo "$pids" | xargs kill 2>/dev/null

# 두 백엔드는 graceful shutdown 이라 잠깐 걸린다.
for _ in $(seq 1 20); do
    [ -z "$(lsof -ti:$PORTS 2>/dev/null)" ] && break
    sleep 0.5
done

leftover="$(lsof -ti:$PORTS 2>/dev/null)"
if [ -n "$leftover" ]; then
    echo "응답 없음, 강제 종료: $(echo "$leftover" | tr '\n' ' ')"
    echo "$leftover" | xargs kill -9 2>/dev/null
    sleep 1
fi

for p in 3000 8000 8001; do
    if lsof -ti:$p >/dev/null 2>&1; then
        printf '  \033[31m✗\033[0m :%s 아직 사용중\n' "$p"
    else
        printf '  \033[32m✓\033[0m :%s 정리됨\n' "$p"
    fi
done
