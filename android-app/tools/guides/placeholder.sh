#!/usr/bin/env bash
# Makes a clearly labelled placeholder guide clip. A real recording replaces it by
# overwriting the same file in app/src/main/res/raw/ (no code change).
# Usage: tools/guides/placeholder.sh <step-id> "<what the real clip will show>"
set -euo pipefail
cd "$(dirname "$0")/../.."
id="$1"; what="$2"
font=app/src/main/res/font/atkinson.ttf
ffmpeg -loglevel error -y -f lavfi -i "color=c=0x0F1626:s=360x640:d=6:r=10" \
  -vf "drawtext=fontfile=$font:text='PLACEHOLDER':fontcolor=0xFFB648:fontsize=34:x=(w-tw)/2:y=170,\
drawtext=fontfile=$font:text='Guide clip not recorded yet':fontcolor=white:fontsize=22:x=(w-tw)/2:y=240,\
drawtext=fontfile=$font:text='$what':fontcolor=0x9CC9FF:fontsize=20:x=(w-tw)/2:y=300" \
  -c:v libopenh264 -b:v 120k -pix_fmt yuv420p -movflags +faststart -an "app/src/main/res/raw/guide_${id}.mp4"
