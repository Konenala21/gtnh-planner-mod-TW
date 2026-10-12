#!/usr/bin/env bash
# proxy.sh <recording> [...]: a 1080p copy of a 4K recording beside it (<name>-1080/, the same frame names and
# frames.csv), for shots that show it full screen: the trailer then decodes a quarter of the pixels a frame, the
# slowest part of a render, and each picture is scaled down once with a good filter. Point the shot at the copy.
#
#   tools/trailer/proxy.sh run/client/recordings/tour3 run/client/recordings/nitro3 run/client/recordings/plat3
set -euo pipefail
for rec in "$@"; do
  rec=${rec%/}
  out="$rec-1080"
  mkdir -p "$out"
  first=$(ls "$rec" | grep -m1 '^f[0-9]*\.jpg$')
  start=$((10#${first:1:5}))
  ffmpeg -loglevel error -y -threads 0 -start_number "$start" -i "$rec/f%05d.jpg" \
    -vf "scale=1920:1080:flags=lanczos" -q:v 2 -start_number "$start" "$out/f%05d.jpg"
  cp "$rec/frames.csv" "$out/frames.csv"
  echo "$out: $(ls "$out" | grep -c '\.jpg$') frames"
done
