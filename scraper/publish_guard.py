#!/usr/bin/env python3
"""
Publish guard: compares a freshly scraped dataset with the one phones currently have and refuses to
publish a clear regression (a source site broke, a parser silently returned nothing, …).
On refusal the job fails → an alert issue is opened, and phones keep the last good data.

    python publish_guard.py out/ https://raw.githubusercontent.com/<owner>/<repo>/data
"""
import json, sys, urllib.request, zipfile

# new / old ratios below these mean "something broke", not "the meta changed"
MIN = {"heroes": 0.97, "counter_edges": 0.60, "strong_edges": 0.60, "heroes_with_tips": 0.90,
       "heroes_with_counters": 0.90, "template_files": 0.85}


def stats(matchups: dict, template_names) -> dict:
    hs = matchups["heroes"]
    return {
        "heroes": len(hs),
        "counter_edges": sum(len(h["counters"]) for h in hs),
        "strong_edges": sum(len(h["strong_against"]) for h in hs),
        "heroes_with_tips": sum(1 for h in hs if h["lane_tips"]),
        "heroes_with_counters": sum(1 for h in hs if h["counters"]),
        "template_files": sum(1 for n in template_names if n.endswith(".png")),
    }


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def main(out_dir: str, base_url: str) -> int:
    new = stats(json.load(open(f"{out_dir}/matchups.json")), zipfile.ZipFile(f"{out_dir}/templates.zip").namelist())
    try:
        old_m = json.loads(fetch(f"{base_url}/matchups.json"))
        import io
        old_t = zipfile.ZipFile(io.BytesIO(fetch(f"{base_url}/templates.zip"))).namelist()
        old = stats(old_m, old_t)
    except Exception as e:
        print(f"guard: no previous dataset to compare ({e}); publishing")
        return 0
    failures = []
    for k, floor in MIN.items():
        ratio = new[k] / old[k] if old[k] else 1.0
        flag = "OK " if ratio >= floor else "LOW"
        print(f"guard: {flag} {k:22s} {old[k]:6d} -> {new[k]:6d}  ({ratio:5.0%}, floor {floor:.0%})")
        if ratio < floor:
            failures.append(f"{k} dropped from {old[k]} to {new[k]}")
    if failures:
        print("guard: REFUSING to publish — " + "; ".join(failures))
        return 1
    print("guard: dataset looks healthy; publishing")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2].rstrip("/")))
