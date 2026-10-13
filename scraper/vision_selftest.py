#!/usr/bin/env python3
"""
Self-test for hero recognition: every hero's own default art must be recognised when shown the way
the draft shows it (zoomed, cropped at the top, sometimes mirrored, video-compressed, on a dark card).

    python vision_selftest.py                 # all heroes, prints failures + a summary
    python vision_selftest.py --hero nolan    # one hero, verbose
    python vision_selftest.py --json out/selftest.json

Exit code 0 even on failures (the weekly job reports them; detection falls back to the picker).
"""
import argparse, json, os, sys
import cv2, numpy as np
sys.argv_backup = sys.argv; sys.argv = [sys.argv[0]]
import vision_lab as L
sys.argv = sys.argv_backup

HERE = os.path.dirname(os.path.abspath(__file__))


def synth_card(art_bgr, zoom, top_cut, mirror, jpeg_q, blur):
    """Place the (square) art on a 134x80 dark card the way the game does: zoom around the face,
    cut the top, optional mirror, then video-style degradation."""
    g = cv2.cvtColor(art_bgr, cv2.COLOR_BGR2GRAY)
    n = g.shape[0]
    size = int(80 * zoom)                       # face art height on the card
    art = cv2.resize(g, (size, size), interpolation=cv2.INTER_AREA)
    if mirror: art = cv2.flip(art, 1)
    card = (np.linspace(40, 15, 80)[:, None] * np.ones((80, 134))).astype(np.uint8)  # dark gradient
    y0 = -int(size * top_cut); x0 = (134 - size) // 2 + 10
    for y in range(80):
        sy = y - y0
        if 0 <= sy < size:
            xs, xe = max(0, x0), min(134, x0 + size)
            card[y, xs:xe] = art[sy, xs - x0:xe - x0]
    if blur: card = cv2.GaussianBlur(card, (3, 3), 0)
    ok, enc = cv2.imencode('.jpg', card, [cv2.IMWRITE_JPEG_QUALITY, jpeg_q])
    return cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)


CASES = [  # (zoom, top_cut, mirror, jpeg_q, blur)
    (1.0, 0.0, False, 85, False), (1.25, 0.15, False, 75, True), (1.25, 0.15, True, 75, True),
    (1.4, 0.25, False, 70, True), (1.4, 0.25, True, 70, True), (0.9, 0.0, True, 80, False),
]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--templates", default=os.path.join(HERE, "out/templates"))
    ap.add_argument("--hero"); ap.add_argument("--json"); a = ap.parse_args()
    index = json.load(open(os.path.join(a.templates, "index.json")))
    templates = L.load_templates(a.templates)
    results = {}
    for hid, e in sorted(index.items()):
        if a.hero and hid != a.hero: continue
        base = cv2.imread(os.path.join(a.templates, e["file"]))
        arts = {"official": base}
        if e.get("variants"): arts["head"] = cv2.imread(os.path.join(a.templates, e["variants"][0]))
        per = []
        for art_name, art in arts.items():
            if art is None: continue
            for (zoom, cut, mir, q, bl) in CASES:
                card = synth_card(art, zoom, cut, mir, q, bl)
                hero, top3 = L.identify(card, templates, try_mirror=True)
                per.append({"art": art_name, "case": f"z{zoom}c{cut}m{int(mir)}", "ok": hero == hid,
                            "top": top3[0][1] if top3 else None, "score": round(top3[0][0], 2) if top3 else None,
                            "second": top3[1][1] if top3 and len(top3) > 1 else None, "margin": round(top3[0][0] - top3[1][0], 2) if top3 and len(top3) > 1 else None})
        passed = sum(p["ok"] for p in per); results[hid] = {"passed": passed, "total": len(per), "cases": per}
        if a.hero or passed < len(per):
            bad = [p for p in per if not p["ok"]]
            print(f"{hid:16s} {passed}/{len(per)}  " + "; ".join(f"{p['art']} {p['case']}: top={p['top']} {p['score']} m{p['margin']}" for p in bad[:4]))
    total = sum(r["total"] for r in results.values()); ok = sum(r["passed"] for r in results.values())
    fails = [h for h, r in results.items() if r["passed"] < r["total"]]
    print(f"\nSELFTEST: {ok}/{total} synthetic cards recognised · {len(results) - len(fails)}/{len(results)} heroes fully pass · failing: {fails}")
    if a.json:
        json.dump({"ok": ok, "total": total, "failing": fails, "results": results}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
