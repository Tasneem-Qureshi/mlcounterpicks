#!/usr/bin/env python3
"""
Writes a short markdown report of a refresh run (posted as an issue comment → emailed to the owner).
Compares the fresh dataset with the one currently published, so it must run BEFORE publishing.

    python run_summary.py out/ https://raw.githubusercontent.com/<owner>/<repo>/data > out/summary.md
"""
import json, sys, urllib.request


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.loads(r.read())


def main(out_dir, base_url):
    new = json.load(open(f"{out_dir}/matchups.json"))
    try:
        old = fetch_json(f"{base_url}/matchups.json")
    except Exception:
        old = None
    nh = {h["hero_id"]: h for h in new["heroes"]}
    lines = [f"**Heroes:** {len(nh)}"]
    if old:
        oh = {h["hero_id"]: h for h in old["heroes"]}
        added = sorted(nh[i]["name"] for i in nh.keys() - oh.keys())
        removed = sorted(oh[i]["name"] for i in oh.keys() - nh.keys())
        if added: lines.append(f"**New heroes:** {', '.join(added)}")
        if removed: lines.append(f"**Removed:** {', '.join(removed)}")
        changed = [nh[i]["name"] for i in nh.keys() & oh.keys()
                   if [e["hero_id"] for e in nh[i]["counters"][:5]] != [e["hero_id"] for e in oh[i]["counters"][:5]]]
        lines.append(f"**Counter lists changed:** {len(changed)} heroes" + (f" ({', '.join(sorted(changed)[:12])}{' …' if len(changed) > 12 else ''})" if changed else ""))
    lines.append(f"**Counter edges:** {sum(len(h['counters']) for h in new['heroes'])} · "
                 f"**heroes with tips:** {sum(1 for h in new['heroes'] if h['lane_tips'])}/{len(nh)}")
    try:
        st = json.load(open(f"{out_dir}/selftest.json"))
        real = st.get("realistic_failing", [])
        lines.append(f"**Recognition self-test:** {len(nh) - len(real)}/{len(nh)} heroes pass realistic framing"
                     + (f" (needs picker: {', '.join(real)})" if real else ""))
    except Exception:
        lines.append("**Recognition self-test:** not run")
    print("\n".join("- " + l for l in lines))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2].rstrip("/"))
