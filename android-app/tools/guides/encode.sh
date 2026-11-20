#!/usr/bin/env bash
# Turns a raw `adb shell screenrecord` capture into a shipped guide clip:
# 360x800, 12 fps, H.264 (OpenH264), no audio, with a caption bar saying where it was recorded.
# Usage: [SPEED=2] tools/guides/encode.sh <raw.mp4> <step-id> ["caption"]   (SPEED shortens slow captures)
set -euo pipefail
cd "$(dirname "$0")/../.."
raw="$1"; id="$2"; cap="${3:-Recorded on Android 15 (emulator). Your phone may look different.}"
font=app/src/main/res/font/atkinson.ttf
ffmpeg -loglevel error -y -i "$raw" -an \
  -vf "${SPEED:+setpts=PTS/$SPEED,}fps=12,scale=360:800:force_original_aspect_ratio=decrease,pad=360:800:(ow-iw)/2:(oh-ih)/2:color=0x070B14,drawbox=x=0:y=ih-34:w=iw:h=34:color=0x070B14@0.85:t=fill,drawtext=fontfile=$font:text='$cap':fontcolor=white:fontsize=12:x=(w-tw)/2:y=h-23" \
  -c:v libopenh264 -b:v 220k -movflags +faststart "app/src/main/res/raw/guide_${id}.mp4"
