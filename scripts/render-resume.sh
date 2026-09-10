#!/usr/bin/env bash
# 이력서 PDF 첫 장을 홈 모달용 PNG 로 렌더한다 (macOS Quick Look). PDF 를 바꾸면 다시 돌린다.
#   scripts/render-resume.sh   → front/public/resume.png
set -euo pipefail
cd "$(dirname "$0")/../front/public"
tmp=$(mktemp -d)
qlmanage -t -s 2480 -o "$tmp" resume.pdf >/dev/null
mv "$tmp/resume.pdf.png" resume.png
echo "wrote front/public/resume.png ($(sips -g pixelWidth -g pixelHeight resume.png | tail -2 | awk '{print $2}' | paste -sd x -))"
