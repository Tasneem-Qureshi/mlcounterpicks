#!/usr/bin/env python3
"""
Offline twin of the app's VisionDetector (Kotlin) for tuning without a phone.

    python vision_lab.py testdata/draft_1280x588_a.jpg            # print per-slot matches
    python vision_lab.py frame.png --annotate out.png             # also draw the boxes

Algorithm (keep in sync with android/.../vision/VisionDetector.kt):
  crop card → gray → resize to 80 px high → CLAHE → for each hero, the inner-64 % face patch of
  its head icon is searched inside the crop at 35–80 % of the crop height (TM_CCOEFF_NORMED);
  coarse pass at 0.6 resolution (3 sizes, all arts) picks 12 candidates, fine pass decides; accept ≥0.74 with ≥0.08 margin (waived ≥0.80).
Layout boxes are [anchor, x, y, w, h] in units of the frame height, from ../android/app/src/main/assets/layouts.json.
"""
import argparse, glob, json, os, re, sys
import cv2, numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
WORK_H, FINE, COARSE, COARSE_F, SHORTLIST, ACCEPT, MARGIN, SURE = 80, tuple(0.35 + 0.05 * i for i in range(10)), (0.40, 0.55, 0.70), 0.6, 12, 0.74, 0.08, 0.80
clahe = cv2.createCLAHE(2.0, (4, 4))


def load_templates(tdir):
    """hero -> list of arts; each art prepared at every scale (mirrors VisionDetector.load)."""
    index_path = os.path.join(tdir, "index.json")
    by_hero = {}
    if os.path.exists(index_path):
        for hid, e in json.load(open(index_path)).items():
            by_hero[hid] = [e.get("file", hid + ".png")] + list(e.get("variants", []))
    else:
        for p in sorted(glob.glob(os.path.join(tdir, "*.png"))):
            f = os.path.basename(p); hid = re.sub(r"_\d+$", "", f[:-4]); by_hero.setdefault(hid, []).append(f)
    out = []
    for hid, files in by_hero.items():
        arts = []
        for f in files:
            im = cv2.imread(os.path.join(tdir, f))
            if im is None: continue
            g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY); n = g.shape[0]; c = int(n * 0.18); face = g[c:n - c, c:n - c]
            def sc(px, face=face): return clahe.apply(cv2.resize(face, (px, px), interpolation=cv2.INTER_AREA))
            arts.append(([sc(max(12, int(WORK_H * s))) for s in FINE], [sc(max(8, int(WORK_H * COARSE_F * s))) for s in COARSE]))
        if arts: out.append((hid, arts))
    return out


def score(img, tpl):
    if tpl.shape[0] >= img.shape[0] or tpl.shape[1] >= img.shape[1]: return -1.0
    return float(cv2.matchTemplate(img, tpl, cv2.TM_CCOEFF_NORMED).max())


def identify(crop, templates, try_mirror=False):
    """Pick cards may be mirrored by the game; search both orientations and keep the better."""
    if try_mirror:
        a = identify(crop, templates); b = identify(cv2.flip(crop, 1), templates)
        sa = a[1][0][0] if a[1] else -1; sb = b[1][0][0] if b[1] else -1
        return b if sb > sa else a
    if crop.std() < 14: return None, None
    s = WORK_H / crop.shape[0]
    work = clahe.apply(cv2.resize(crop, (int(crop.shape[1] * s), WORK_H), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR))
    half = cv2.resize(work, (int(work.shape[1] * COARSE_F), int(work.shape[0] * COARSE_F)), interpolation=cv2.INTER_AREA)
    coarse = sorted(templates, key=lambda t: -max(score(half, c) for a in t[1] for c in a[1]))[:SHORTLIST]
    ranked = sorted(((max(score(work, f) for a in t[1] for f in a[0]), t[0]) for t in coarse), reverse=True)
    best, second = ranked[0], ranked[1]
    ok = best[0] >= ACCEPT and (best[0] >= SURE or best[0] - second[0] >= MARGIN)
    return (best[1] if ok else None), ranked[:3]


def content_rect(gray):
    r = gray.mean(axis=1); c = gray.mean(axis=0); thr = 10
    top = next((i for i, v in enumerate(r) if v >= thr), 0); bottom = len(r) - next((i for i, v in enumerate(r[::-1]) if v >= thr), 0)
    left = next((i for i, v in enumerate(c) if v >= thr), 0); right = len(c) - next((i for i, v in enumerate(c[::-1]) if v >= thr), 0)
    H, W = gray.shape
    if top < H * 0.02: top = 0
    if H - bottom < H * 0.02: bottom = H
    if left < W * 0.02: left = 0
    if W - right < W * 0.02: right = W
    return left, top, right, bottom


def boxes(layout, content):
    l, t, r, b = content; H = b - t
    def rect(a):
        anchor, x, y, w, h = a; wp = w * H
        x0 = r - x * H - wp if anchor.upper() == "R" else l + x * H
        return int(x0), int(t + y * H), int(x0 + wp), int(t + (y + h) * H)
    return {k: [rect(a) for a in layout.get(k, [])] for k in ("ally_slots", "enemy_slots", "ally_bans", "enemy_bans")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frame"); ap.add_argument("--annotate"); ap.add_argument("--layout", default=os.path.join(HERE, "../android/app/src/main/assets/layouts.json"))
    ap.add_argument("--templates", default=os.path.join(HERE, "out/templates"))
    a = ap.parse_args()
    frame = cv2.imread(a.frame); gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    layout = json.load(open(a.layout))["profiles"][0]
    content = content_rect(gray); print("frame", gray.shape[::-1], "content", content)
    templates = load_templates(a.templates)
    colors = {"ally_slots": (255, 255, 0), "enemy_slots": (0, 0, 255), "ally_bans": (255, 0, 255), "enemy_bans": (255, 0, 255)}
    for kind, rects in boxes(layout, content).items():
        for i, (x0, y0, x1, y1) in enumerate(rects):
            hero, top3 = identify(gray[y0:y1, x0:x1], templates, try_mirror=kind.endswith("_slots"))
            print(f"{kind:12s} {i}: {hero or '-':12s} top3={[(n, round(s, 2)) for s, n in (top3 or [])]}")
            if a.annotate:
                cv2.rectangle(frame, (x0, y0), (x1, y1), colors[kind], 2)
                cv2.putText(frame, hero or "-", (x0, y1 + 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
    if a.annotate:
        cv2.imwrite(a.annotate, frame); print("wrote", a.annotate)


if __name__ == "__main__":
    sys.exit(main())
