#!/usr/bin/env python3
"""Subset Material Symbols Outlined to only the icons used in the codebase.

Resolves icon ligature names → glyph IDs via the font's GSUB table,
then subsets to only those glyphs. Produces a tiny woff2.

Requires: pip install fonttools brotli
"""
import os
import re
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CLIENT_DIR = os.path.dirname(SCRIPT_DIR)
FULL_FONT = os.path.join(CLIENT_DIR, "static/fonts/material-symbols-outlined-full.woff2")
OUT_FONT = os.path.join(CLIENT_DIR, "static/fonts/material-symbols-outlined.woff2")


def collect_icons() -> set[str]:
    icons = set()

    # icons.ts — all quoted icon names
    icons_ts = os.path.join(CLIENT_DIR, "src/lib/icons.ts")
    with open(icons_ts) as f:
        icons.update(re.findall(r"'([a-z_]+)'", f.read()))

    # Hardcoded in templates: material-symbols-outlined">icon_name</span>
    src_dir = os.path.join(CLIENT_DIR, "src")
    for root, _, files in os.walk(src_dir):
        for fname in files:
            if not fname.endswith((".svelte", ".ts")):
                continue
            with open(os.path.join(root, fname)) as f:
                content = f.read()
            # Direct text content after class
            icons.update(re.findall(
                r'material-symbols-outlined["\s>]+([a-z_]+)', content
            ))
            # icon: 'xxx' patterns (e.g. density options)
            icons.update(re.findall(r"icon:\s*'([a-z_]+)'", content))

    return icons


def resolve_icon_unicodes(font_path: str, icon_names: set[str]) -> tuple[set[int], set[str]]:
    """Resolve icon names to Unicode codepoints via cmap reverse lookup."""
    from fontTools.ttLib import TTFont

    font = TTFont(font_path)
    cmap = font.getBestCmap()
    glyph_to_cp = {v: k for k, v in cmap.items()}

    resolved = set()
    unresolved = set()

    for name in icon_names:
        cp = glyph_to_cp.get(name)
        if cp:
            resolved.add(cp)
        else:
            unresolved.add(name)

    font.close()
    return resolved, unresolved


def main():
    if not os.path.exists(FULL_FONT):
        print(f"ERROR: Full font not found at {FULL_FONT}")
        print("Download it first:")
        print(f"  curl -sL 'https://fonts.gstatic.com/s/materialsymbolsoutlined/v318/"
              f"kJEhBvYX7BgnkSrUwT8OhrdQw4oELdPIeeII9v6oFsLjBuVY.woff2' -o '{FULL_FONT}'")
        sys.exit(1)

    icons = collect_icons()
    print(f"Found {len(icons)} unique icons:")
    print(", ".join(sorted(icons)))
    print()

    unicodes, unresolved = resolve_icon_unicodes(FULL_FONT, icons)

    print(f"Resolved {len(unicodes)} icon codepoints")
    if unresolved:
        print(f"WARNING: {len(unresolved)} icons not found in cmap: {', '.join(sorted(unresolved))}")

    # Build unicode range: basic Latin (for ligature input a-z, _) + icon codepoints
    unicode_ranges = ["U+0000-007F"]
    for cp in sorted(unicodes):
        unicode_ranges.append(f"U+{cp:04X}")

    unicode_str = ",".join(unicode_ranges)

    # Keep rclt/rlig (the features Material Symbols uses for ligatures)
    cmd = [
        "pyftsubset", FULL_FONT,
        f"--unicodes={unicode_str}",
        "--layout-features=rclt,rlig",
        "--no-layout-closure",
        "--flavor=woff2",
        f"--output-file={OUT_FONT}",
    ]

    print(f"Running: {' '.join(cmd[:3])} ... --output-file=...")
    subprocess.run(cmd, check=True)

    full_size = os.path.getsize(FULL_FONT)
    out_size = os.path.getsize(OUT_FONT)
    print()
    print(f"Full font:   {full_size // 1024}KB")
    print(f"Subset font: {out_size // 1024}KB")
    print(f"Saved:       {(full_size - out_size) // 1024}KB ({100 - (out_size * 100 // full_size)}%)")
    print(f"\nOutput: {OUT_FONT}")


if __name__ == "__main__":
    main()
