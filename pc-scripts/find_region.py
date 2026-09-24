#!/usr/bin/env python3
"""Locate a UI button by colour when the app is canvas / WebView rendered and
`uiautomator dump` returns no child nodes.

Usage:
  python find_region.py screenshot.png purple|pink|dark|red|blue
Prints the largest matching-colour region's bounding box and center tap point.
Tune the thresholds in match() for your button.
"""
import sys

from PIL import Image


def match(r, g, b, mode):
    if mode == "purple":
        return b > 190 and 110 < r < 205 and g < 185 and (b - g) > 55 and (b - r) > 30
    if mode == "pink":
        return r > 185 and g < 130 and 60 < b < 175 and (r - g) > 70
    if mode == "red":
        return r > 180 and g < 90 and b < 90
    if mode == "blue":
        return b > 180 and r < 110 and g < 150
    if mode == "dark":
        mx, mn = max(r, g, b), min(r, g, b)
        return mx < 110 and (mx - mn) < 45
    return False


def largest_region(path, mode, min_area=300):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    px = img.load()
    mask = [[match(*px[x, y], mode) for x in range(w)] for y in range(h)]
    seen = [[False] * w for _ in range(h)]
    best = None
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            if mask[y][x] and not seen[y][x]:
                stack = [(x, y)]; seen[y][x] = True
                minx = maxx = x; miny = maxy = y; cnt = 0
                while stack:
                    cx, cy = stack.pop(); cnt += 1
                    minx = min(minx, cx); maxx = max(maxx, cx)
                    miny = min(miny, cy); maxy = max(maxy, cy)
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h and mask[ny][nx] and not seen[ny][nx]:
                            seen[ny][nx] = True; stack.append((nx, ny))
                if cnt > min_area and (best is None or cnt > best[0]):
                    best = (cnt, minx, miny, maxx, maxy)
    return best, (w, h)


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    path, mode = sys.argv[1], sys.argv[2]
    best, (w, h) = largest_region(path, mode)
    if not best:
        print("NO_REGION"); sys.exit(0)
    cnt, minx, miny, maxx, maxy = best
    print(f"IMG={w}x{h} mode={mode}")
    print(f"AREA={cnt} BBOX=[{minx},{miny}][{maxx},{maxy}]")
    print(f"TAP={(minx+maxx)//2} {(miny+maxy)//2}")


if __name__ == "__main__":
    main()
