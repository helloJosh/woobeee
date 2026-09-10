#!/usr/bin/env bash
# 홈 배너 두 장(라이트/다크)을 헤드리스 크롬으로 찍는다. 문구·색을 바꾸면 scripts/home-banner-{light,dark}.html 을 고치고 다시 돌린다.
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for mode in light dark; do
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --window-size=3840,766 --force-device-scale-factor=1 \
    --virtual-time-budget=8000 --screenshot="front/public/home-banner-$mode.png" "file://$PWD/scripts/home-banner-$mode.html" 2>/dev/null
  echo "wrote front/public/home-banner-$mode.png"
done
