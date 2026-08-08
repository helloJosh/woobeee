#!/usr/bin/env bash
#
# 로컬 개발 서버 셋을 백그라운드로 띄운다.
#
#   app-mvc     :8000  → app-mvc.log
#   app-webflux :8001  → app-webflux.log
#   front       :3000  → front/dev.log
#
# nohup 으로 띄우므로 터미널을 닫아도 살아 있다. 내릴 때는 scripts/dev-down.sh.
#
# 실패를 조용히 넘기지 않는 것이 이 스크립트의 요점이다 — `nohup ... &` 는 프로세스가
# 즉사해도 프롬프트가 그냥 돌아오므로, 뜬 줄 알고 넘어가기 쉽다. 그래서 끝에서 포트를
# 확인하고, 하나라도 안 떴으면 로그 꼬리를 보여주고 0 이 아닌 값으로 끝낸다.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MVC_JAR="app-mvc/target/app-mvc.jar"
WEBFLUX_JAR="app-webflux/target/app-webflux.jar"

fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
info() { printf '\033[36m%s\033[0m\n' "$*"; }

# ── 사전 조건 ────────────────────────────────────────────────────────────────
[ -f "$MVC_JAR" ] || fail "없음: $MVC_JAR
  먼저 빌드해야 한다:
    export JAVA_HOME=\$(/usr/libexec/java_home -v 25)
    ./mvnw -pl app-mvc,app-webflux -am package"

[ -f "$WEBFLUX_JAR" ] || fail "없음: $WEBFLUX_JAR (위와 같은 명령으로 빌드)"

[ -d front/node_modules ] || fail "없음: front/node_modules — cd front && npm install"

# 인프라는 포트로 확인한다. 컨테이너 이름은 compose 프로젝트마다 다를 수 있다.
for probe in "postgres:9432" "redis:9379" "minio:9000"; do
    name="${probe%%:*}"; port="${probe##*:}"
    nc -z localhost "$port" 2>/dev/null || fail "$name (:$port) 에 연결되지 않는다.
  docker compose -f .docker-compose/docker-compose.yml up -d"
done

# ── 기존 프로세스 정리 ───────────────────────────────────────────────────────
# 포트를 쥔 프로세스를 먼저 치운다. 이게 없으면 두 번째 실행부터 백엔드가
# "Port 8000 was already in use" 로 죽는데, 로그를 안 보면 알 수 없다.
existing="$(lsof -ti:3000,8000,8001 2>/dev/null)"
if [ -n "$existing" ]; then
    info "이미 떠 있는 프로세스를 내린다: $(echo "$existing" | tr '\n' ' ')"
    echo "$existing" | xargs kill 2>/dev/null
    for _ in $(seq 1 20); do
        [ -z "$(lsof -ti:3000,8000,8001 2>/dev/null)" ] && break
        sleep 0.5
    done
    # 안 죽는 것은 강제로. graceful shutdown 이 걸려 있어 대개 여기까진 안 온다.
    leftover="$(lsof -ti:3000,8000,8001 2>/dev/null)"
    [ -n "$leftover" ] && echo "$leftover" | xargs kill -9 2>/dev/null
fi

# ── 기동 ─────────────────────────────────────────────────────────────────────
info "app-mvc     → :8000  (app-mvc.log)"
nohup java -jar "$MVC_JAR" > app-mvc.log 2>&1 &

info "app-webflux → :8001  (app-webflux.log)"
nohup java -jar "$WEBFLUX_JAR" > app-webflux.log 2>&1 &

info "front       → :3000  (front/dev.log)"
( cd front && nohup npm run dev > dev.log 2>&1 & )

# ── 확인 ─────────────────────────────────────────────────────────────────────
# 고정 sleep 대신 포트가 열릴 때까지 기다린다. 셋 다 뜨면 즉시 통과한다.
info "기동 대기…"
deadline=$((SECONDS + 60))
while [ $SECONDS -lt $deadline ]; do
    up=0
    for p in 8000 8001 3000; do
        lsof -ti:$p >/dev/null 2>&1 && up=$((up + 1))
    done
    [ $up -eq 3 ] && break
    sleep 1
done

echo
failed=0
for entry in "app-mvc:8000:app-mvc.log" "app-webflux:8001:app-webflux.log" "front:3000:front/dev.log"; do
    name="${entry%%:*}"; rest="${entry#*:}"; port="${rest%%:*}"; log="${rest##*:}"
    if lsof -ti:"$port" >/dev/null 2>&1; then
        printf '  \033[32m✓\033[0m %-12s :%s\n' "$name" "$port"
    else
        printf '  \033[31m✗\033[0m %-12s :%s  — %s 마지막 줄:\n' "$name" "$port" "$log"
        tail -5 "$log" 2>/dev/null | sed 's/^/      /'
        failed=1
    fi
done

echo
if [ $failed -eq 0 ]; then
    echo "  http://localhost:3000"
    echo "  로그: tail -f app-mvc.log app-webflux.log front/dev.log"
    echo "  종료: scripts/dev-down.sh"
else
    fail "일부가 뜨지 않았다. 위 로그를 확인한다."
fi
