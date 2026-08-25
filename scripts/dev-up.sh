#!/usr/bin/env bash
#
# 로컬 개발 서버 셋을 백그라운드로 띄운다.
#
#   app-mvc     :8000  → logs/app-mvc.log   (PID: app-mvc.pid)
#   app-webflux :8001  → logs/app-webflux.log (PID: app-webflux.pid)
#   front       :3000  → front/front.log
#
# 두 백엔드의 stdout 은 버린다. logback 이 이미 logs/ 에 같은 내용을 쓰고 날짜별로 돌리기
# 때문에(application.yaml 의 logging.file.name), 리다이렉트를 두면 루트에 중복 사본이 하나 더
# 쌓인다. stderr 만 logs/*.stderr.log 로 받는다 — logback 은 stdout 을 쓰므로 여기엔 JVM 이
# 죽는 수준의 사고(잘못된 jar, 클래스 버전 불일치)만 남고, 그건 logs/ 에 안 찍힌다.
#
# 프론트는 기본이 **프로덕션 빌드**다(`next build` → `next start`). 이 호스트가 공개 도메인
# (www.woobeee.com)을 서빙하기 때문이다 — 개발 서버를 그대로 내보내면 번들이 최소화되지 않고,
# HMR 웹소켓이 인터넷에 열리고, React Strict Mode 가 이펙트를 두 번 실행해 같은 API 를 두 번
# 호출한다. 핫리로드로 개발할 때만 `--dev` 를 붙인다.
#
#   scripts/dev-up.sh          # 프로덕션 프론트 (기본)
#   scripts/dev-up.sh --dev    # 개발 서버 프론트 (핫리로드)
#
# nohup 으로 띄우므로 터미널을 닫아도 살아 있다. 내릴 때는 scripts/dev-down.sh.
#
# 실패를 조용히 넘기지 않는 것이 이 스크립트의 요점이다 — `nohup ... &` 는 프로세스가
# 즉사해도 프롬프트가 그냥 돌아오므로, 뜬 줄 알고 넘어가기 쉽다. 그래서 끝에서 포트를
# 확인하고, 하나라도 안 떴으면 로그 꼬리를 보여주고 0 이 아닌 값으로 끝낸다.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FRONT_MODE=prod
for arg in "$@"; do
    case "$arg" in
        --dev)  FRONT_MODE=dev ;;
        --prod) FRONT_MODE=prod ;;
        *) printf '알 수 없는 인자: %s (쓸 수 있는 것: --dev, --prod)\n' "$arg" >&2; exit 2 ;;
    esac
done

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

# jar 가 소스보다 오래됐으면 경고한다. 이 스크립트는 **빌드하지 않고** 있는 jar 를 실행하므로,
# 재시작만 반복하면 옛 코드가 계속 뜬다 — 실제로 한 번 겪었다(고친 줄 알았던 이미지 경로가
# 3주 전 jar 로 돌고 있었다). 자동으로 빌드하지는 않는다: 몇 분 걸리고 실패할 수도 있어서
# 사용자가 정할 일이다.
newest_src="$(find core/src app-mvc/src app-webflux/src -type f \( -name '*.java' -o -name '*.yaml' -o -name '*.sql' \) -newer "$MVC_JAR" -print -quit 2>/dev/null)"
if [ -n "$newest_src" ]; then
    printf '\033[33m⚠ jar 이 소스보다 오래됐다\033[0m — 예: %s\n' "$newest_src" >&2
    printf '  이대로 띄우면 옛 코드가 뜬다. 반영하려면:\n' >&2
    printf '    ./mvnw -pl app-mvc,app-webflux -am package -DskipTests\n\n' >&2
fi

# 인프라는 포트로 확인한다. 컨테이너 이름은 compose 프로젝트마다 다를 수 있다.
for probe in "postgres:9432" "redis:9379" "minio:9000"; do
    name="${probe%%:*}"; port="${probe##*:}"
    nc -z localhost "$port" 2>/dev/null || fail "$name (:$port) 에 연결되지 않는다.
  docker compose -f .docker-compose/docker-compose.yml up -d"
done

# ── 프론트 프로덕션 빌드 (기동 전에) ────────────────────────────────────────
# 포트를 죽이기 **전에** 빌드한다. 뒤에서 하면 빌드하는 40초 동안 공개 사이트가 내려간다.
# next start 는 .next 를 실행할 뿐 다시 빌드하지 않으므로, 매번 빌드해서 서빙되는 것이
# 항상 지금 소스와 일치하게 한다(낡은 jar 로 한 번 겪은 함정을 프론트에서 반복하지 않는다).
if [ "$FRONT_MODE" = prod ]; then
    info "front       → 프로덕션 빌드 중… (front/front.log)"
    ( cd front && npm run build > front.log 2>&1 ) \
        || fail "프론트 빌드 실패 — 기존 서버는 그대로 살려 둔다. 마지막 줄:
$(tail -15 front/front.log 2>/dev/null | sed 's/^/      /')"
fi

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
mkdir -p logs

info "app-mvc     → :8000  (logs/app-mvc.log)"
nohup java -jar "$MVC_JAR" > /dev/null 2> logs/app-mvc.stderr.log &
echo $! > app-mvc.pid

info "app-webflux → :8001  (logs/app-webflux.log)"
nohup java -jar "$WEBFLUX_JAR" > /dev/null 2> logs/app-webflux.stderr.log &
echo $! > app-webflux.pid

if [ "$FRONT_MODE" = prod ]; then
    info "front       → :3000  (front/front.log)"
    ( cd front && nohup npm start >> front.log 2>&1 & )
else
    info "front       → :3000  개발 서버 (front/front.log)"
    ( cd front && nohup npm run dev > front.log 2>&1 & )
fi

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
for entry in "app-mvc:8000:logs/app-mvc.log" "app-webflux:8001:logs/app-webflux.log" "front:3000:front/front.log"; do
    name="${entry%%:*}"; rest="${entry#*:}"; port="${rest%%:*}"; log="${rest##*:}"
    if lsof -ti:"$port" >/dev/null 2>&1; then
        printf '  \033[32m✓\033[0m %-12s :%s\n' "$name" "$port"
    else
        printf '  \033[31m✗\033[0m %-12s :%s\n' "$name" "$port"
        # stderr 를 먼저 본다. logback 이 붙기 전에 죽었다면 logs/*.log 는 비어 있고 원인은
        # 여기에만 있다.
        stderr_log="logs/$(basename "$log" .log).stderr.log"
        if [ -s "$stderr_log" ]; then
            printf '      %s 마지막 줄:\n' "$stderr_log"
            tail -8 "$stderr_log" | sed 's/^/        /'
        fi
        if [ -s "$log" ]; then
            printf '      %s 마지막 줄:\n' "$log"
            tail -8 "$log" | sed 's/^/        /'
        fi
        failed=1
    fi
done

echo
if [ $failed -eq 0 ]; then
    echo "  http://localhost:3000"
    echo "  로그: tail -f logs/app-mvc.log logs/app-webflux.log front/front.log"
    [ "$FRONT_MODE" = prod ] && echo "  프론트: 프로덕션 빌드 (핫리로드가 필요하면 --dev)"
    echo "  종료: scripts/dev-down.sh"
else
    fail "일부가 뜨지 않았다. 위 로그를 확인한다."
fi
