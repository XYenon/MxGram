#!/usr/bin/env bash
set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null; then
    echo "Missing required command: $1 (run via: nix develop -c $0)" >&2
    exit 1
  fi
}

require_command python3
require_command rsvg-convert
require_command magick

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tgs_source="${1:-$repo_root/app/src/test/resources/AnimatedSticker.tgs}"
out_dir="$repo_root/app/src/test/resources/animated-sticker-frames"

python3 - "$tgs_source" "$out_dir" <<'PY'
from io import BytesIO
import os
import subprocess
import sys

from lottie.exporters.svg import export_svg
from lottie.parsers.tgs import parse_tgs

tgs, out_dir = sys.argv[1], sys.argv[2]
anim = parse_tgs(tgs)
frame_count = int(anim.out_point - anim.in_point)
os.makedirs(out_dir, exist_ok=True)

for i in range(int(anim.in_point), int(anim.in_point) + frame_count):
    svg = f"/tmp/animated-sticker-frame-{i:02d}.svg"
    png = f"{out_dir}/frame-{i:02d}.png"
    webp = f"{out_dir}/frame-{i:02d}.webp"
    buf = BytesIO()
    export_svg(anim, buf, frame=i, pretty=False)
    with open(svg, "wb") as handle:
        handle.write(buf.getvalue())
    subprocess.check_call(["rsvg-convert", "-w", "512", "-h", "512", "-o", png, svg])
    subprocess.check_call([
        "magick", png, "-define", "webp:lossless=true", webp,
    ])
    os.remove(png)
    print(f"rendered frame {i}")
PY

echo "Rendered frames into $out_dir"
