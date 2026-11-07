#!/usr/bin/env python3
"""
Resolution sweep: does recognition survive low screen resolutions (Samsung HD+ "50 %", and lower)?

    python vision_ressweep.py                    # synthetic sweep over all heroes + real frames
    python vision_ressweep.py --json out/ressweep.json

The phone captures the screen at ≤1280 px long edge; a draft card is 13.6 % of the frame height.
So a 1544x720 (HD+) screen gives 81 px cards, 960x540 gives 73 px, 854x394 gives 54 px, …
Each card crop is resized to 80 px high before matching, so what matters is how much detail
survives at the card's native pixel height. This sweeps card heights 80 → 24 px.
"""
import argparse, glob, json, os, sys
import cv2, numpy as np
sys.argv_backup = sys.argv; sys.argv = [sys.argv[0]]
import vision_lab as L
import vision_selftest as S
sys.argv = sys.argv_backup
HERE = os.path.dirname(os.path.abspath(__file__))

CARD_H = (80, 64, 56, 48, 40, 32)
FRAME_H = (540, 480, 394, 320, 240)
CI_CARD_H = (80, 64, 56, 48, 40)        # 80 = full res, 56 ≈ Samsung HD+ (1544x720 → 1280x597), 40 = safety margin
CI_FRAME_H = (480, 394, 320)
# realistic framings only (zoom, top_cut, mirror)
FRAMINGS = ((1.0, 0.0, False), (1.25, 0.15, False), (1.25, 0.15, True), (0.9, 0.0, True))


def shrink(card, h):
    """Render the 134x80 card at the pixel height a low-res screen would give, with video compression."""
    if h == card.shape[0]: return card
    small = cv2.resize(card, (int(round(134 * h / 80)), h), interpolation=cv2.INTER_AREA)
    ok, enc = cv2.imencode('.jpg', small, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)


_G = {}
def _init(tdir):
    cv2.setNumThreads(1)
    _G["templates"] = L.load_templates(tdir); _G["tdir"] = tdir


def _one_hero(item):
    hid, e = item; tdir = _G["tdir"]; templates = _G["templates"]
    arts = [cv2.imread(os.path.join(tdir, e["file"]))]
    if e.get("variants"): arts.append(cv2.imread(os.path.join(tdir, e["variants"][0])))
    per_h = {}
    for h in CARD_H:
        fails = []
        for ai, art in enumerate(a for a in arts if a is not None):
            for (zoom, cut, mir) in FRAMINGS:
                card = shrink(S.synth_card(art, zoom, cut, mir, 85, False), h)
                hero, top3 = L.identify(card, templates, try_mirror=True)
                if hero != hid: fails.append((ai, f"z{zoom}c{cut}m{int(mir)}", top3[0][1] if top3 else None, round(top3[0][0], 2) if top3 else None))
        per_h[h] = fails
    return hid, per_h


def synthetic(index, tdir, only=None, workers=None):
    import multiprocessing as mp
    items = [(h, e) for h, e in sorted(index.items()) if not only or h == only]
    with mp.get_context("spawn").Pool(workers or max(1, os.cpu_count() - 1), initializer=_init, initargs=(tdir,)) as pool:
        done = 0; out = {}
        for hid, per_h in pool.imap_unordered(_one_hero, items):
            out[hid] = per_h; done += 1
            if done % 10 == 0: print(f"  … {done}/{len(items)} heroes", flush=True)
    return out


def real_frames(templates, layout):
    frames = sorted(glob.glob(os.path.join(HERE, "../testdata/draft_*.jpg")))
    out = {}
    for fp in frames:
        img = cv2.imread(fp); gray0 = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        def run(gray):
            res = {}
            for kind, rects in L.boxes(layout, L.content_rect(gray)).items():
                for i, (x0, y0, x1, y1) in enumerate(rects):
                    hero, _ = L.identify(gray[y0:y1, x0:x1], templates, try_mirror=kind.endswith("_slots"))
                    if hero: res[f"{kind}/{i}"] = hero
            return res
        base = run(gray0); per_h = {}
        for h in FRAME_H:
            if h > gray0.shape[0]: continue
            g = cv2.resize(gray0, (int(round(gray0.shape[1] * h / gray0.shape[0])), h), interpolation=cv2.INTER_AREA)
            ok, enc = cv2.imencode('.jpg', g, [cv2.IMWRITE_JPEG_QUALITY, 85]); g = cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)
            r = run(g)
            per_h[h] = {"kept": sum(1 for k, v in base.items() if r.get(k) == v), "of": len(base),
                        "lost": [f"{k}={v}" for k, v in base.items() if r.get(k) != v], "new": [f"{k}={v}" for k, v in r.items() if k not in base]}
        out[os.path.basename(fp)] = {"base": base, "sweep": per_h}
    return out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--templates", default=os.path.join(HERE, "out/templates"))
    ap.add_argument("--layout", default=os.path.join(HERE, "../android/app/src/main/assets/layouts.json"))
    ap.add_argument("--hero"); ap.add_argument("--json"); ap.add_argument("--no-real", action="store_true")
    ap.add_argument("--ci", action="store_true", help="fewer card sizes (the ones that matter for real phones) so the weekly job stays short")
    a = ap.parse_args()
    global CARD_H, FRAME_H
    if a.ci: CARD_H = CI_CARD_H; FRAME_H = CI_FRAME_H
    index = json.load(open(os.path.join(a.templates, "index.json")))
    syn = synthetic(index, a.templates, a.hero)
    print("SYNTHETIC (all heroes, realistic framings) — card px height → heroes passing every framing")
    summary = {}
    for h in CARD_H:
        failing = [hid for hid, per in syn.items() if per[h]]
        summary[h] = failing
        print(f"  card {h:3d}px ≈ frame {int(h / 0.136):4d}px: {len(syn) - len(failing)}/{len(syn)}" + (f"  failing: {failing[:12]}{'…' if len(failing) > 12 else ''}" if failing else ""))
    if a.hero:
        for h, f in syn[a.hero].items(): print(h, f)
    real = {}
    if not a.no_real:
        layout = json.load(open(a.layout))["profiles"][0]
        real = real_frames(L.load_templates(a.templates), layout)
        print("\nREAL FRAMES downscaled — frame height → detections kept / baseline")
        for h in FRAME_H:
            rows = [(f, r["sweep"][h]) for f, r in real.items() if h in r["sweep"]]
            if not rows: continue
            kept = sum(s["kept"] for _, s in rows); of = sum(s["of"] for _, s in rows); new = sum(len(s["new"]) for _, s in rows)
            lost = [f"{f}:{x}" for f, s in rows for x in s["lost"]]
            print(f"  {h:4d}px: {kept}/{of} kept, {new} new" + (f"  lost: {lost[:8]}" if lost else ""))
    if a.json:
        json.dump({"synthetic_failing_by_card_h": {str(k): v for k, v in summary.items()}, "real": real}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
