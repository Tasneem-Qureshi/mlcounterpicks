#!/usr/bin/env python3
"""
MLBB Draft Companion — hero / counter / lane-tip scraper & aggregator.

Produces:
  out/matchups.json   strictly-typed dataset (schema v1) the Android app ingests
  out/templates/      96x96 hero portraits for on-device template matching
  out/templates.zip   same portraits, zipped for the in-app updater
  out/manifest.json   data_version + sha256 + URLs; the app polls this to self-update

Sources (public, read-only, robots.txt-respecting, rate-limited):
  fandom      mobile-legends.fandom.com  hero list, class, specialties, lanes, icons (MediaWiki API)
  gms         api.gms.moontontech.com    Moonton's website API: in-game "head" art = the draft-card painting
  mlbbhub     mlbbhub.com/counter/<slug> ranked win-rate edges + kit reasons + official icons
  mlbbio      mlbb.io/en/hero/<slug>/counter   ranked edges + item answers + play-against tips
  mlcounter   mlcounter.com/heroes/<slug>/     weak/strong-against lists, item counters, lane tips
  overrides   overrides.json (local, hand-curated; always wins)

  mlbbcounters.com was evaluated and skipped: every hero URL in its sitemap 404s.

Design notes / best practices:
  * Every response is cached on disk (.cache/); `--offline` rebuilds from cache only.
  * robots.txt honoured per host; UA rotation; jittered delays; bounded concurrency; retries.
  * One canonical `hero_id` slug everywhere; alias table absorbs cross-site spellings.
  * Multi-source merge: numeric edges are averaged, list-only sources contribute rank-based
    scores, and every edge records which sources agreed (`sources`) and a `confidence`.
  * Counter graph is symmetrised (A ∈ B.counters  <=>  B ∈ A.strong_against).
  * Output validated against the schema before writing; carries schema_version,
    generated_at, data_version so the app can decide when to re-seed.

Usage:
  python scraper.py                                  # full run
  python scraper.py --limit 8                        # smoke test
  python scraper.py --offline                        # from cache only
  python scraper.py --android-dir ../android/app/src/main   # also copy into app assets
  python scraper.py --base-url https://raw.githubusercontent.com/<you>/<repo>/data   # manifest URLs
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import io
import json
import logging
import random
import re
import shutil
import sys
import time
import unicodedata
import zipfile
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import unquote, urlparse, parse_qs
from urllib.robotparser import RobotFileParser

import httpx
from bs4 import BeautifulSoup, Tag

try:
    from PIL import Image
except ImportError:  # portraits become optional
    Image = None  # type: ignore

log = logging.getLogger("mlbb-scraper")

SCHEMA_VERSION = 1
ROLES = ("EXP", "GOLD", "MID", "ROAM", "JUNGLE")
HERO_CLASSES = ("Tank", "Fighter", "Assassin", "Mage", "Marksman", "Support")

USER_AGENTS = [
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
]

FANDOM_API = "https://mobile-legends.fandom.com/api.php"
MLBBHUB = "https://mlbbhub.com"
MLBBIO = "https://mlbb.io"
MLCOUNTER = "https://mlcounter.com"

LANE_ALIASES = {
    "exp lane": "EXP", "exp": "EXP", "explane": "EXP", "exp-lane": "EXP", "offlane": "EXP",
    "gold lane": "GOLD", "gold": "GOLD", "goldlane": "GOLD", "gold-lane": "GOLD",
    "mid lane": "MID", "mid": "MID", "midlane": "MID", "mid-lane": "MID", "middle": "MID", "middle lane": "MID",
    "roam": "ROAM", "roamer": "ROAM", "roaming": "ROAM", "roam lane": "ROAM",
    "jungle": "JUNGLE", "jungler": "JUNGLE", "jungling": "JUNGLE", "jungle lane": "JUNGLE",
}

# Site-specific spellings -> canonical slug (keys are norm_key()'d).  Anything not
# listed falls through to the generic slugifier, which is right for 95% of heroes.
NAME_ALIASES = {
    "yisunshin": "yi_sun_shin", "yi_sunshin": "yi_sun_shin", "yss": "yi_sun_shin",
    "change": "chang_e", "chang_e": "chang_e", "chang_e_": "chang_e",
    "xborg": "x_borg", "x_borg": "x_borg",
    "yuzhong": "yu_zhong", "luoyi": "luo_yi", "lapulapu": "lapu_lapu", "lapu_lapu": "lapu_lapu",
    "popol_kupa": "popol_and_kupa", "popolkupa": "popol_and_kupa", "popol": "popol_and_kupa",
    "popol_and_kupa": "popol_and_kupa", "popol_kupa_": "popol_and_kupa",
    "zhu_xin": "zhuxin", "su_you": "suyou", "cici": "cici", "ci_ci": "cici",
}

SPECIALTY_TIPS = {
    "Regen": "Rush anti-heal (Sea Halberd / Dominance Ice) before the first big trade — their sustain is the matchup.",
    "Crowd Control": "Carry Purify or Tough Boots; do not step forward without the enemy's CC on cooldown.",
    "Burst": "Buy an early defensive item for their damage type and avoid trading at full enemy mana / energy.",
    "Chase": "Keep a dash or Flicker for disengage; they win extended chases, not short trades.",
    "Poke": "Back off the wave when low — they out-trade you at range. All-in only when they are out of mana.",
    "Push": "Match their lane pressure; they punish absent laners by taking towers fast.",
    "Reap": "Stay above execute thresholds — do not sit at low HP under tower.",
    "Magic Damage": "Prioritise Athena's Shield / Radiant Armor over physical defense in this lane.",
    "Damage": "Respect their first item spike; trade before, not after, they complete it.",
    "Guard": "They thrive protecting a carry — split them from their team instead of fighting into the group.",
    "Initiator": "Track their engage cooldown; punish the window right after a missed engage.",
    "Control": "Respect zone control — fight on your side of the wave, not theirs.",
    "Support": "Pressure their carry while they are pulled away to help the lane.",
    "Mixed Damage": "Balance Athena's Shield and Antique Cuirass — they deal both magic and physical damage.",
}


# --------------------------------------------------------------------------- #
# Normalisation
# --------------------------------------------------------------------------- #
def norm_key(name: str) -> str:
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower().strip()
    s = re.sub(r"[’'`.]", "_", s)
    s = re.sub(r"[^a-z0-9]+", "_", s)
    return s.strip("_")


def slugify(name: str) -> str:
    """Canonical hero_id: 'Yi Sun-shin' -> 'yi_sun_shin', "Chang'e" -> 'chang_e', 'X.Borg' -> 'x_borg'."""
    key = norm_key(name)
    if key in NAME_ALIASES:
        return NAME_ALIASES[key]
    compact = key.replace("_", "")
    if compact in NAME_ALIASES:
        return NAME_ALIASES[compact]
    return key


def canonical_lane(text: str) -> Optional[str]:
    return LANE_ALIASES.get(text.strip().lower())


def clean_text(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("\xa0", " ")).strip()


GENERIC_REASON = re.compile(r"largest edge among|based on ranked data, so expect|measured counters", re.I)


def sentence(s: str) -> str:
    s = clean_text(re.sub(r"^[\s•\-–·*]+", "", s))
    if s and s[-1] not in ".!?":
        s += "."
    return s


# --------------------------------------------------------------------------- #
# Data model (mirrors the Android Room schema)
# --------------------------------------------------------------------------- #
@dataclass
class Edge:
    hero_id: str
    role: str                       # lane the *other* hero usually plays
    edge_score: float               # 0..5 ; >=4 hard counter, ~2.5 unmeasured kit edge
    reason: str
    confidence: str = "ranked"      # ranked | kit | community | curated | derived
    sources: list[str] = field(default_factory=list)


@dataclass
class Hero:
    hero_id: str
    name: str
    hero_class: str = ""
    specialties: list[str] = field(default_factory=list)
    primary_role: str = ""
    secondary_roles: list[str] = field(default_factory=list)
    portrait_url: str = ""
    alt_portrait_url: str = ""      # second art source (in-game icon from the wiki) for template matching
    wiki_code: str = ""             # "95" for Hero951-icon.png → used to find portrait + skin icons
    art_urls: list[str] = field(default_factory=list)  # extra template arts (wiki portrait, skin icons)
    portrait_file: str = ""
    counters: list[Edge] = field(default_factory=list)        # heroes that beat THIS hero
    strong_against: list[Edge] = field(default_factory=list)  # heroes THIS hero beats
    lane_tips: list[str] = field(default_factory=list)        # how to play AGAINST this hero
    item_counters: list[str] = field(default_factory=list)
    win_rate: Optional[float] = None
    sources: list[str] = field(default_factory=list)

    def to_json(self) -> dict:
        d = asdict(self)
        return d


# --------------------------------------------------------------------------- #
# Polite HTTP client: disk cache, robots.txt, UA rotation, throttling, retries
# --------------------------------------------------------------------------- #
class PoliteClient:
    def __init__(self, cache_dir: Path, offline: bool, concurrency: int, min_delay: float, max_delay: float):
        self.cache_dir = cache_dir
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.offline = offline
        self.sem = asyncio.Semaphore(concurrency)
        self.min_delay, self.max_delay = min_delay, max_delay
        self._robots: dict[str, RobotFileParser] = {}
        self._robots_lock = asyncio.Lock()
        self._last_hit: dict[str, float] = {}
        self._client = httpx.AsyncClient(timeout=30, follow_redirects=True)
        self.stats = {"cache_hits": 0, "fetched": 0, "errors": 0}

    async def close(self):
        await self._client.aclose()

    def _cache_path(self, url: str) -> Path:
        h = hashlib.sha1(url.encode()).hexdigest()[:24]
        return self.cache_dir / urlparse(url).netloc.replace(":", "_") / h

    def _headers(self, url: str = "") -> dict:
        h = {"User-Agent": random.choice(USER_AGENTS), "Accept-Language": "en-US,en;q=0.9",
             "Accept": "text/html,application/json;q=0.9,image/*;q=0.8,*/*;q=0.7",
             "sec-ch-ua": '"Chromium";v="124", "Google Chrome";v="124"', "sec-ch-ua-mobile": "?0", "sec-ch-ua-platform": '"macOS"'}
        if re.search(r"\.(png|jpe?g|webp)(\?|/|$)", url) or "static.wikia" in url or "akmweb" in url:
            h.update({"Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                      "Sec-Fetch-Dest": "image", "Sec-Fetch-Mode": "no-cors", "Sec-Fetch-Site": "cross-site",
                      "Referer": "https://mobile-legends.fandom.com/" if "wikia" in url else "https://mlbbhub.com/"})
        return h

    async def _allowed(self, url: str) -> bool:
        p = urlparse(url)
        base = f"{p.scheme}://{p.netloc}"
        async with self._robots_lock:
            if base not in self._robots:
                rp = RobotFileParser()
                try:
                    r = await self._client.get(base + "/robots.txt", headers=self._headers())
                    rp.parse(r.text.splitlines() if r.status_code == 200 else [])
                except Exception:
                    rp.parse([])
                self._robots[base] = rp
        return self._robots[base].can_fetch("*", url)

    async def _throttle(self, host: str):
        wait = random.uniform(self.min_delay, self.max_delay) - (time.monotonic() - self._last_hit.get(host, 0.0))
        if wait > 0:
            await asyncio.sleep(wait)
        self._last_hit[host] = time.monotonic()

    async def get(self, url: str) -> Optional[bytes]:
        path = self._cache_path(url)
        if path.exists():
            self.stats["cache_hits"] += 1
            data = path.read_bytes()
            return None if data == b"__404__" else data
        if self.offline:
            return None
        if not await self._allowed(url):
            log.warning("robots.txt disallows %s", url)
            return None
        host = urlparse(url).netloc
        async with self.sem:
            await self._throttle(host)
            for attempt in range(3):
                try:
                    r = await self._client.get(url, headers=self._headers(url))
                    if r.status_code == 404:
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(b"__404__")  # negative cache
                        self.stats["errors"] += 1
                        return None
                    if r.status_code in (429, 503):
                        await asyncio.sleep(5 * (attempt + 1))
                        continue
                    r.raise_for_status()
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(r.content)
                    self.stats["fetched"] += 1
                    return r.content
                except httpx.HTTPError as e:
                    log.warning("fetch failed (%d/3) %s: %s", attempt + 1, url, e)
                    await asyncio.sleep(2 * (attempt + 1))
        self.stats["errors"] += 1
        return None

    async def post(self, url: str, body: str) -> Optional[bytes]:
        """JSON POST with the same cache / throttle / retry policy as get()."""
        key = url + "#" + hashlib.sha1(body.encode()).hexdigest()[:12]
        path = self._cache_path(key)
        if path.exists():
            self.stats["cache_hits"] += 1
            return path.read_bytes()
        if self.offline:
            return None
        host = urlparse(url).netloc
        async with self.sem:
            await self._throttle(host)
            for attempt in range(3):
                try:
                    r = await self._client.post(url, content=body, headers={**self._headers(), "Content-Type": "application/json"})
                    if r.status_code in (429, 503):
                        await asyncio.sleep(5 * (attempt + 1))
                        continue
                    r.raise_for_status()
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(r.content)
                    self.stats["fetched"] += 1
                    return r.content
                except httpx.HTTPError as e:
                    log.warning("post failed (%d/3) %s: %s", attempt + 1, url, e)
                    await asyncio.sleep(2 * (attempt + 1))
        self.stats["errors"] += 1
        return None

    async def soup(self, url: str) -> Optional[BeautifulSoup]:
        raw = await self.get(url)
        if raw is None:
            return None
        s = BeautifulSoup(raw, "lxml")
        for t in s(["script", "style", "svg", "noscript"]):
            t.decompose()
        return s


# --------------------------------------------------------------------------- #
# Source: Fandom wiki hero list
# --------------------------------------------------------------------------- #
class FandomSource:
    name = "fandom"

    def __init__(self, client: PoliteClient):
        self.client = client

    async def heroes(self) -> dict[str, Hero]:
        raw = await self.client.get(f"{FANDOM_API}?action=parse&page=List_of_heroes&prop=text&format=json")
        if not raw:
            log.error("Fandom hero list unavailable")
            return {}
        soup = BeautifulSoup(json.loads(raw)["parse"]["text"]["*"], "lxml")
        table = soup.select_one("table.wikitable")
        if table is None:
            log.error("Fandom: wikitable not found (layout changed?)")
            return {}
        headers = [clean_text(th.get_text()).lower() for th in table.select("tr th")]

        def col(*needles) -> Optional[int]:
            for i, h in enumerate(headers):
                if all(n in h for n in needles):
                    return i
            return None

        c_icon, c_hero, c_role, c_spec, c_lane = col("icon"), col("hero"), col("role"), col("special"), col("lane")
        heroes: dict[str, Hero] = {}
        for tr in table.select("tr"):
            tds = tr.find_all("td")
            if len(tds) < 6 or c_hero is None:
                continue
            bold = tds[c_hero].find("b")
            name = clean_text((bold.get_text() if bold else tds[c_hero].get_text()).split(",")[0])
            if not name:
                continue
            classes = [clean_text(a.get_text()) for a in tds[c_role].find_all("a")] if c_role is not None else []
            classes = [c for c in classes if c in HERO_CLASSES]
            specs = [clean_text(a.get_text()) for a in tds[c_spec].find_all("a")] if c_spec is not None else []
            lanes: list[str] = []
            if c_lane is not None:
                for a in tds[c_lane].find_all("a"):
                    lane = canonical_lane(a.get_text())
                    if lane and lane not in lanes:
                        lanes.append(lane)
            icon = ""
            code = ""
            if c_icon is not None and (img := tds[c_icon].find("img")):
                if (mm := re.match(r"Hero(\d+)(\d)-icon\.png", img.get("data-image-name", ""))):
                    code = mm.group(1)
                icon = img.get("data-src") or img.get("src") or ""
                icon = re.sub(r"/revision/latest/scale-to-width-down/\d+", "/revision/latest", icon)
                if icon.startswith("data:"):
                    icon = ""
            if not lanes:
                lanes = [self._default_lane(classes)]
            hid = slugify(name)
            heroes[hid] = Hero(hero_id=hid, name=name, hero_class=classes[0] if classes else "",
                               specialties=specs, primary_role=lanes[0], secondary_roles=lanes[1:],
                               portrait_url=icon, wiki_code=code, sources=[self.name])
        log.info("fandom: %d heroes", len(heroes))
        return heroes

    async def arts(self, hero: Hero) -> list[str]:
        """Extra template art from the wiki: the in-game circular icon (differs from the website icon after reworks)."""
        if not hero.wiki_code:
            return []
        titles = [f"File:Hero{hero.wiki_code}1-icon.png"]
        url = f"{FANDOM_API}?action=query&prop=imageinfo&iiprop=url&format=json&titles=" + "|".join(titles).replace(" ", "_")
        raw = await self.client.get(url)
        if not raw:
            return []
        pages = json.loads(raw).get("query", {}).get("pages", {})
        found = {}
        for p in pages.values():
            info = p.get("imageinfo")
            if info:
                found[p["title"].replace(" ", "_")] = info[0]["url"]
        return [found[t.replace(" ", "_")] for t in titles if t.replace(" ", "_") in found]

    @staticmethod
    def _default_lane(classes: list[str]) -> str:
        m = {"Tank": "ROAM", "Support": "ROAM", "Marksman": "GOLD", "Mage": "MID", "Assassin": "JUNGLE", "Fighter": "EXP"}
        return next((m[c] for c in classes if c in m), "EXP")


# --------------------------------------------------------------------------- #
# Source: Moonton's own website API (api.gms.moontontech.com).  Its hero records carry the
# in-game "head" art (the exact painting the draft-pick cards show) and the circular icon.
# --------------------------------------------------------------------------- #
GMS_HERO_LIST = "https://api.gms.moontontech.com/api/gms/source/2669606/2756564"


class GmsSource:
    name = "gms"

    def __init__(self, client: PoliteClient):
        self.client = client

    async def heads(self) -> dict[str, dict]:
        """hero_id -> {"head": url, "head_big": url, "icon": url}"""
        body = json.dumps({"pageSize": 200, "pageIndex": 1, "sorts": [], "filters": []})
        raw = await self.client.post(GMS_HERO_LIST, body)
        if not raw:
            log.warning("gms: hero list unavailable")
            return {}
        out = {}
        for rec in json.loads(raw).get("data", {}).get("records", []):
            d = rec.get("data", {})
            name = d.get("hero", {}).get("data", {}).get("name") or re.sub(r"^\d+", "", rec.get("caption", ""))
            if not name:
                continue
            out[slugify(name)] = {"head": d.get("head", ""), "head_big": d.get("head_big", ""),
                                  "icon": d.get("hero", {}).get("data", {}).get("head", "")}
        log.info("gms: %d heroes with head art", len(out))
        return out


# --------------------------------------------------------------------------- #
# Counter sources.  Each returns a SourceResult for one hero.
# --------------------------------------------------------------------------- #
@dataclass
class SourceResult:
    counters: list[Edge] = field(default_factory=list)
    strong_against: list[Edge] = field(default_factory=list)
    tips: list[str] = field(default_factory=list)
    items: list[str] = field(default_factory=list)
    win_rate: Optional[float] = None
    icon: str = ""


def pp_to_score(pp: float) -> float:
    """Win-rate edge (percentage points) -> 0..5.  +1pp≈3.0, +2.5pp≈4.2, ≥3.5pp→5."""
    return round(min(5.0, max(0.5, 2.2 + 0.8 * pp)), 2)


def rank_to_score(rank: int, n: int) -> float:
    """Unranked-but-ordered lists: top entry 4.0 sliding down to 3.0."""
    if n <= 1:
        return 3.5
    return round(4.0 - 1.0 * rank / (n - 1), 2)


class MlbbHubSource:
    name = "mlbbhub"
    PP_RE = re.compile(r"win rate edge of ([\d.]+) percentage points", re.I)

    def __init__(self, client: PoliteClient):
        self.client = client

    @staticmethod
    def slug(name: str) -> str:
        s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
        s = re.sub(r"['’.]", "", s)
        return re.sub(r"[^a-z0-9]+", "-", s).strip("-")

    async def fetch(self, hero: Hero) -> SourceResult:
        res = SourceResult()
        soup = await self.client.soup(f"{MLBBHUB}/counter/{self.slug(hero.name)}")
        if soup is None:
            return res
        if m := re.search(r"([\d.]+)\s*%\s*WR", soup.get_text(" ")):
            res.win_rate = float(m.group(1))
        if img := soup.find("img", alt=re.compile(rf"^{re.escape(hero.name)} hero icon", re.I)):
            res.icon = self._unproxy(img.get("src", ""))
        for h in soup.find_all(["h2", "h3"]):
            title = clean_text(h.get_text()).lower()
            if title.startswith("proven counters"):
                res.counters += self._rows(h, hero, "ranked")
            elif title.startswith("kit matchups"):
                res.counters += self._rows(h, hero, "kit")
            elif "is strong against" in title:
                res.strong_against += self._rows(h, hero, "ranked")
        return res

    def _rows(self, heading: Tag, hero: Hero, confidence: str) -> list[Edge]:
        edges, seen = [], set()
        node: Optional[Tag] = heading
        while True:
            node = node.find_next()  # type: ignore[union-attr]
            if node is None or (node.name in ("h2", "h3")):
                break
            if node.name != "a" or not str(node.get("href", "")).startswith("/counter/"):
                continue
            name_el = node.find("span", class_=re.compile(r"\btruncate\b")) or next(
                (sp for sp in node.find_all("span", class_=re.compile("font-bold"))
                 if not re.fullmatch(r"\d+", clean_text(sp.get_text()))), None)
            if not name_el:
                continue
            hid = slugify(clean_text(name_el.get_text()))
            if hid == hero.hero_id or hid in seen:
                continue
            seen.add(hid)
            reason_el = node.find("p")
            reason = sentence(reason_el.get_text()) if reason_el else ""
            if GENERIC_REASON.search(reason):
                reason = ""
            pp = None
            if (sr := node.find("span", class_="sr-only")) and (mm := self.PP_RE.search(sr.get_text())):
                pp = float(mm.group(1))
            lane_el = node.find(attrs={"aria-label": re.compile("lane", re.I)})
            role = canonical_lane(lane_el["aria-label"].replace(" lane", "")) if lane_el else ""
            score = pp_to_score(pp) if pp is not None else (2.5 if confidence == "kit" else 2.0)
            edges.append(Edge(hid, role or "", score, reason, confidence, [self.name]))
        return edges

    @staticmethod
    def _unproxy(src: str) -> str:
        try:
            q = parse_qs(urlparse(src).query)
            if "url" in q:
                return unquote(q["url"][0])
        except Exception:
            pass
        return src


class MlbbIoSource:
    name = "mlbbio"
    ROW_RE = re.compile(r"^\s*(\d+)\s*$")

    def __init__(self, client: PoliteClient):
        self.client = client
        self.slugs: dict[str, str] = {}  # hero_id -> site slug

    async def load_slugs(self):
        raw = await self.client.get(f"{MLBBIO}/sitemap-0.xml")
        if not raw:
            return
        for loc in re.findall(r"<loc>https://mlbb\.io/en/hero/([^/<]+)</loc>", raw.decode("utf-8", "ignore")):
            self.slugs[slugify(loc.replace("-", " "))] = loc
        log.info("mlbbio: %d hero slugs from sitemap", len(self.slugs))

    async def fetch(self, hero: Hero) -> SourceResult:
        res = SourceResult()
        slug = self.slugs.get(hero.hero_id) or MlbbHubSource.slug(hero.name)
        soup = await self.client.soup(f"{MLBBIO}/en/hero/{slug}/counter")
        if soup is None:
            return res
        text = soup.get_text("|")
        # "1|Masha| — |wins the straight damage trade ...|Strong|+2.6%|win rate advantage"
        for m in re.finditer(r"\|(\d)\|([^|]+)\|\s*—\s*\|([^|]+)\|(Strong|Slight|Moderate|Hard)?\|?\+([\d.]+)%\|win rate advantage", text):
            hid = slugify(clean_text(m.group(2)))
            if hid == hero.hero_id:
                continue
            res.counters.append(Edge(hid, "", pp_to_score(float(m.group(5))), sentence(m.group(3)), "ranked", [self.name]))
        if m := re.search(r"(\d+\.\d+)% win rate(?! advantage)", text):
            res.win_rate = float(m.group(1))
        for h in soup.find_all(["h2", "h3"]):
            title = clean_text(h.get_text()).lower()
            if title.startswith("items that answer"):
                res.items += self._pairs(h)
            elif title.startswith("how to play against"):
                res.tips += [sentence(li.get_text()) for li in self._until_next_heading(h, "li")]
        return res

    @staticmethod
    def _until_next_heading(h: Tag, tag: str) -> list[Tag]:
        out, node = [], h
        while (node := node.find_next()) is not None and node.name not in ("h2", "h3"):
            if node.name == tag:
                out.append(node)
        return out

    def _pairs(self, h: Tag) -> list[str]:
        """'Blade Armor' + '+80 Physical Defense blunts the physical damage' -> one sentence."""
        texts = [clean_text(t) for t in h.find_next().stripped_strings] if h.find_next() else []
        out, node = [], h
        while (node := node.find_next()) is not None and node.name not in ("h2", "h3"):
            if node.name in ("div", "li") and node.find(string=True, recursive=False) is None:
                parts = [clean_text(s) for s in node.stripped_strings]
                if len(parts) == 2 and len(parts[0]) < 30:
                    out.append(f"{parts[0]}: {sentence(parts[1])}")
        # de-dup while preserving order
        seen, res = set(), []
        for t in out:
            if t not in seen:
                seen.add(t)
                res.append(t)
        return res[:4]


class MlCounterSource:
    name = "mlcounter"

    def __init__(self, client: PoliteClient):
        self.client = client
        self.slugs: dict[str, str] = {}

    async def load_slugs(self):
        soup = await self.client.soup(f"{MLCOUNTER}/")
        if soup is None:
            return
        for a in soup.select('a[href^="https://mlcounter.com/heroes/"]'):
            href = a["href"].rstrip("/")
            site_slug = href.rsplit("/", 1)[-1]
            if not site_slug or site_slug == "heroes":
                continue
            label = clean_text(a.get_text())
            hid = slugify(label) if label else slugify(site_slug)
            self.slugs.setdefault(hid, site_slug)
            self.slugs.setdefault(slugify(site_slug), site_slug)
        log.info("mlcounter: %d hero slugs", len(self.slugs))

    async def fetch(self, hero: Hero) -> SourceResult:
        res = SourceResult()
        slug = self.slugs.get(hero.hero_id) or hero.hero_id.replace("_", "")
        soup = await self.client.soup(f"{MLCOUNTER}/heroes/{slug}/")
        if soup is None:
            return res
        if m := re.search(r"Win rate:\s*([\d.]+)%", soup.get_text(" ")):
            res.win_rate = float(m.group(1))
        for strong in soup.find_all("strong"):
            t = clean_text(strong.get_text()).lower()
            if t.endswith("is weak against"):
                res.counters += self._figure_list(strong, hero)
            elif t.endswith("is strong against") or t == "is strong against":
                res.strong_against += self._figure_list(strong, hero)
        for h in soup.find_all(["h2", "h3", "h4"]):
            title = clean_text(h.get_text()).lower()
            if title.startswith("items counter"):
                res.items += self._item_sentences(h)
            elif title.startswith("tips counter"):
                res.tips += self._tips(h)
            elif title.startswith("how to counter") and " in " in title:
                p = h.find_next("p")
                if p:
                    lane = title.split(" in ", 1)[1].replace(":", "").strip()
                    res.tips.insert(0, f"In {lane}: {sentence(p.get_text())}")
        return res

    def _figure_list(self, anchor: Tag, hero: Hero) -> list[Edge]:
        """Hero figures follow the <strong> label until the next <strong>/<h2>."""
        names, node = [], anchor
        while (node := node.find_next()) is not None and node.name not in ("strong", "h2", "h3"):
            if node.name == "figcaption":
                n = clean_text(node.get_text())
                hid = slugify(n)
                if hid and hid != hero.hero_id and hid not in names:
                    names.append(hid)
        return [Edge(hid, "", rank_to_score(i, len(names)), "", "community", [self.name]) for i, hid in enumerate(names)]

    @staticmethod
    def _item_sentences(h: Tag) -> list[str]:
        out, node = [], h
        while (node := node.find_next()) is not None and node.name not in ("h2", "h3"):
            if node.name == "p":
                t = clean_text(node.get_text())
                if 20 < len(t) < 220 and ("item" in t.lower() or any(k in t for k in ("Halberd", "Ice", "Armor", "Wand", "Crown", "Nature", "Immortality"))):
                    out.append(sentence(t))
        return out[:4]

    @staticmethod
    def _tips(h: Tag) -> list[str]:
        out, node = [], h
        while (node := node.find_next()) is not None and node.name not in ("h2", "h3"):
            if node.name in ("li", "p"):
                t = clean_text(node.get_text())
                if 25 < len(t) < 240 and not t.lower().startswith("how to counter"):
                    out.append(sentence(t))
            if len(out) >= 5:
                break
        return out


# --------------------------------------------------------------------------- #
# Merge / aggregate
# --------------------------------------------------------------------------- #
CONF_RANK = {"curated": 4, "ranked": 3, "community": 2, "kit": 1, "derived": 0}


def merge_edges(existing: list[Edge], incoming: list[Edge]):
    """Average numeric scores per pair; keep the best reason; union the sources."""
    by_id = {e.hero_id: e for e in existing}
    for e in incoming:
        cur = by_id.get(e.hero_id)
        if cur is None:
            by_id[e.hero_id] = Edge(e.hero_id, e.role, e.edge_score, e.reason, e.confidence, list(e.sources))
            existing.append(by_id[e.hero_id])
            continue
        if cur.confidence == "curated":
            continue
        n = max(1, len(cur.sources))
        cur.edge_score = round((cur.edge_score * n + e.edge_score) / (n + 1), 2)
        # agreement between independent sources is itself evidence: small bonus
        if e.sources and not set(e.sources) & set(cur.sources):
            cur.edge_score = round(min(5.0, cur.edge_score + 0.15), 2)
        if (not cur.reason) or (e.reason and len(e.reason) > len(cur.reason) and CONF_RANK[e.confidence] >= CONF_RANK[cur.confidence]):
            cur.reason = e.reason or cur.reason
        if CONF_RANK[e.confidence] > CONF_RANK[cur.confidence]:
            cur.confidence = e.confidence
        cur.role = cur.role or e.role
        for s in e.sources:
            if s not in cur.sources:
                cur.sources.append(s)


def apply_overrides(heroes: dict[str, Hero], overrides: dict):
    for hid, o in overrides.get("heroes", {}).items():
        hero = heroes.get(hid)
        if hero is None:
            log.warning("override for unknown hero %s (skipped)", hid)
            continue
        if "primary_role" in o:
            hero.primary_role = o["primary_role"]
        if "secondary_roles" in o:
            hero.secondary_roles = list(o["secondary_roles"])
        for key in ("counters", "strong_against"):
            for e in o.get(key, []):
                edge = Edge(slugify(e["hero_id"]), e.get("role", ""), float(e["edge_score"]),
                            e.get("reason", ""), "curated", ["overrides"])
                lst: list[Edge] = getattr(hero, key)
                lst[:] = [x for x in lst if x.hero_id != edge.hero_id]
                lst.append(edge)
        if o.get("lane_tips"):
            hero.lane_tips = list(o["lane_tips"]) + [t for t in hero.lane_tips if t not in o["lane_tips"]]
        if o.get("item_counters"):
            hero.item_counters = list(o["item_counters"]) + hero.item_counters
        if "overrides" not in hero.sources:
            hero.sources.append("overrides")


def symmetrise(heroes: dict[str, Hero]):
    for b in heroes.values():
        for e in b.counters:
            if (a := heroes.get(e.hero_id)) and not any(x.hero_id == b.hero_id for x in a.strong_against):
                a.strong_against.append(Edge(b.hero_id, b.primary_role, e.edge_score, e.reason, "derived", list(e.sources)))
    for a in list(heroes.values()):
        for e in a.strong_against:
            if (b := heroes.get(e.hero_id)) and not any(x.hero_id == a.hero_id for x in b.counters):
                b.counters.append(Edge(a.hero_id, a.primary_role, e.edge_score, e.reason, "derived", list(e.sources)))


def finalize(heroes: dict[str, Hero], max_edges: int):
    for h in heroes.values():
        for e in h.counters + h.strong_against:
            if not e.role and e.hero_id in heroes:
                e.role = heroes[e.hero_id].primary_role
            if not e.reason:
                other = heroes[e.hero_id].name if e.hero_id in heroes else e.hero_id
                e.reason = (f"{other} wins the matchup against {h.name} in ranked data."
                            if e in h.counters else f"{h.name} wins the matchup against {other} in ranked data.")
        h.counters = sorted((e for e in h.counters if e.hero_id in heroes), key=lambda e: (-e.edge_score, e.hero_id))[:max_edges]
        h.strong_against = sorted((e for e in h.strong_against if e.hero_id in heroes), key=lambda e: (-e.edge_score, e.hero_id))[:max_edges]

        tips = list(h.lane_tips)
        tips += [f"Build {t}" if not t[0].isupper() else t for t in h.item_counters[:2]]
        for e in [e for e in h.counters if e.role == h.primary_role and e.reason and e.confidence != "derived"][:2]:
            tips.append(f"{heroes[e.hero_id].name}: {e.reason}")
        for s in h.specialties:
            if tip := SPECIALTY_TIPS.get(s):
                tips.append(tip)
        seen, out = set(), []
        for t in tips:
            k = re.sub(r"[^a-z]", "", t.lower())[:60]
            if k and k not in seen:
                seen.add(k)
                out.append(t)
        h.lane_tips = out[:6]
        h.item_counters = h.item_counters[:6]


def validate(doc: dict):
    assert doc["schema_version"] == SCHEMA_VERSION
    ids = {h["hero_id"] for h in doc["heroes"]}
    assert len(ids) == len(doc["heroes"]), "duplicate hero_id"
    for h in doc["heroes"]:
        assert re.fullmatch(r"[a-z0-9_]+", h["hero_id"]), h["hero_id"]
        assert h["primary_role"] in ROLES, (h["hero_id"], h["primary_role"])
        assert all(r in ROLES for r in h["secondary_roles"])
        for key in ("counters", "strong_against"):
            for e in h[key]:
                assert e["hero_id"] in ids, (h["hero_id"], e["hero_id"])
                assert 0 <= e["edge_score"] <= 5
                assert e["role"] in ROLES, (h["hero_id"], e)
                assert e["confidence"] in CONF_RANK
        assert all(isinstance(t, str) for t in h["lane_tips"])


# --------------------------------------------------------------------------- #
# Portrait templates
# --------------------------------------------------------------------------- #
def _std(im) -> float:
    g = im.convert("L")
    px = list(g.getdata())
    m = sum(px) / len(px)
    return (sum((v - m) ** 2 for v in px) / len(px)) ** 0.5


def dhash(im, hash_size: int = 8) -> str:
    """Difference hash — identical algorithm to PerceptualHash.kt on Android."""
    g = im.convert("L").resize((hash_size + 1, hash_size), Image.LANCZOS)
    px = list(g.getdata())
    bits = 0
    for r in range(hash_size):
        for c in range(hash_size):
            bits = (bits << 1) | (1 if px[r * (hash_size + 1) + c] > px[r * (hash_size + 1) + c + 1] else 0)
    return f"{bits:016x}"


async def download_portraits(client: PoliteClient, heroes: dict[str, Hero], out_dir: Path, size: int, max_variants: int = 5):
    if Image is None:
        log.warning("Pillow missing; skipping portraits")
        return
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    index: dict[str, dict] = {}

    async def fetch_square(url: str, hero_id: str):
        raw = await client.get(url)
        if not raw:
            return None
        try:
            im = Image.open(io.BytesIO(raw)).convert("RGBA")
        except Exception as e:
            log.warning("bad image %s: %s", hero_id, e)
            return None
        w, hh = im.size
        side = min(w, hh)
        im = im.crop(((w - side) // 2, (hh - side) // 2, (w - side) // 2 + side, (hh - side) // 2 + side))
        im = im.resize((size, size), Image.LANCZOS)
        bg = Image.new("RGB", im.size, (0, 0, 0))  # flatten: draft slots are dark
        bg.paste(im, mask=im.split()[3])
        return bg

    async def one(h: Hero):
        if not h.portrait_url:
            return
        bg = await fetch_square(h.portrait_url, h.hero_id)
        if bg is None:
            return
        fname = f"{h.hero_id}.png"
        bg.save(out_dir / fname, optimize=True)
        h.portrait_file = f"templates/{fname}"
        entry = {"file": fname, "dhash": dhash(bg), "name": h.name, "variants": []}
        # Extra arts for vision: the draft card uses the in-game portrait / skin art, which is not
        # always the official-site icon.  Every visibly different art becomes "<id>_<n>.png".
        hashes = [int(dhash(bg), 16)]
        candidates = list(dict.fromkeys(h.art_urls + ([h.alt_portrait_url] if h.alt_portrait_url else [])))
        for n, url in enumerate(candidates[:max_variants], start=1):
            alt = await fetch_square(url, h.hero_id)
            if alt is None:
                continue
            hv = int(dhash(alt), 16)
            if alt.convert("L").getextrema()[1] - alt.convert("L").getextrema()[0] < 60 or _std(alt) < 30:
                continue
            if n == 1 or all(bin(hv ^ x).count("1") > 6 for x in hashes):
                hashes.append(hv)
                vname = f"{h.hero_id}_{n}.png"
                alt.save(out_dir / vname, optimize=True)
                entry["variants"].append(vname)
        index[h.hero_id] = entry

    await asyncio.gather(*(one(h) for h in heroes.values()))
    (out_dir / "index.json").write_text(json.dumps(index, indent=1, sort_keys=True))
    log.info("portraits: %d heroes, %d template files", len(index), len(index) + sum(len(e["variants"]) for e in index.values()))


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------- #
# Pipeline
# --------------------------------------------------------------------------- #
async def run(args) -> int:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    client = PoliteClient(Path(args.cache_dir), args.offline, args.concurrency, args.min_delay, args.max_delay)
    try:
        heroes = await FandomSource(client).heroes()
        if not heroes:
            return 2
        if args.limit:
            heroes = dict(list(heroes.items())[: args.limit])

        fandom_src = FandomSource(client)
        gms = await GmsSource(client).heads()

        async def gather_arts(h: Hero):
            g = gms.get(h.hero_id, {})
            # order matters: the GMS head is the draft-card painting itself → first variant
            h.art_urls = [u for u in (g.get("head", ""),) if u] + await fandom_src.arts(h)
            if g.get("icon") and not h.portrait_url:
                h.portrait_url = g["icon"]
        await asyncio.gather(*(gather_arts(h) for h in heroes.values()))
        log.info("arts: gms head for %d, wiki icon for %d of %d heroes",
                 sum(1 for h in heroes.values() if h.hero_id in gms), sum(1 for h in heroes.values() if len(h.art_urls) > 1), len(heroes))

        hub, mio, mlc = MlbbHubSource(client), MlbbIoSource(client), MlCounterSource(client)
        await asyncio.gather(mio.load_slugs(), mlc.load_slugs())
        sources = [s for s in (hub, mio, mlc) if s.name not in set(args.skip_source)]

        async def enrich(h: Hero):
            results = await asyncio.gather(*(s.fetch(h) for s in sources), return_exceptions=True)
            for src, r in zip(sources, results):
                if isinstance(r, Exception):
                    log.warning("%s failed for %s: %s", src.name, h.hero_id, r)
                    continue
                if not (r.counters or r.strong_against or r.tips or r.items):
                    continue
                merge_edges(h.counters, r.counters)
                merge_edges(h.strong_against, r.strong_against)
                h.lane_tips += r.tips
                h.item_counters += r.items
                if r.win_rate is not None and h.win_rate is None:
                    h.win_rate = r.win_rate
                if r.icon and args.prefer_official_icons and r.icon != h.portrait_url:
                    if h.portrait_url and not h.alt_portrait_url:
                        h.alt_portrait_url = h.portrait_url   # keep the wiki icon as a second template
                    h.portrait_url = r.icon
                h.sources.append(src.name)

        await asyncio.gather(*(enrich(h) for h in heroes.values()))
        for s in sources:
            n = sum(1 for h in heroes.values() if s.name in h.sources)
            log.info("%s: data for %d/%d heroes", s.name, n, len(heroes))

        ov = Path(args.overrides)
        if ov.exists():
            apply_overrides(heroes, json.loads(ov.read_text()))

        symmetrise(heroes)
        finalize(heroes, args.max_edges)

        tdir = out_dir / "templates"
        if not args.no_portraits:
            await download_portraits(client, heroes, tdir, args.template_size)

        generated = datetime.now(timezone.utc)
        doc = {
            "schema_version": SCHEMA_VERSION,
            "generated_at": generated.isoformat(timespec="seconds"),
            "data_version": int(generated.timestamp()),
            "sources": sorted({s for h in heroes.values() for s in h.sources}),
            "hero_count": len(heroes),
            "heroes": [h.to_json() for h in sorted(heroes.values(), key=lambda x: x.hero_id)],
        }
        validate(doc)
        out_file = out_dir / "matchups.json"
        out_file.write_text(json.dumps(doc, indent=2, ensure_ascii=False))
        log.info("wrote %s (%d heroes, %d counter edges)", out_file, len(heroes), sum(len(h.counters) for h in heroes.values()))

        manifest = {
            "schema_version": SCHEMA_VERSION,
            "data_version": doc["data_version"],
            "generated_at": doc["generated_at"],
            "hero_count": len(heroes),
            "matchups": {"url": f"{args.base_url}/matchups.json", "sha256": sha256_of(out_file), "bytes": out_file.stat().st_size},
        }
        if tdir.exists():
            zpath = out_dir / "templates.zip"
            with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(tdir.iterdir()):
                    z.write(f, f.name)
            manifest["templates"] = {"url": f"{args.base_url}/templates.zip", "sha256": sha256_of(zpath), "bytes": zpath.stat().st_size}
        (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))

        if args.android_dir:
            assets = Path(args.android_dir) / "assets"
            assets.mkdir(parents=True, exist_ok=True)
            shutil.copy2(out_file, assets / "matchups.json")
            if tdir.exists():
                dst = assets / "templates"
                if dst.exists():
                    shutil.rmtree(dst)
                shutil.copytree(tdir, dst)
            log.info("copied into Android assets: %s", assets)
        log.info("http: %s", client.stats)
        return 0
    finally:
        await client.close()


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="out")
    p.add_argument("--cache-dir", default=".cache")
    p.add_argument("--overrides", default="overrides.json")
    p.add_argument("--android-dir", default="", help="e.g. ../android/app/src/main")
    p.add_argument("--base-url", default="https://example.invalid/data", help="where manifest.json/matchups.json/templates.zip will be hosted")
    p.add_argument("--offline", action="store_true")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--skip-source", action="append", default=[], choices=["mlbbhub", "mlbbio", "mlcounter"])
    p.add_argument("--concurrency", type=int, default=3)
    p.add_argument("--min-delay", type=float, default=0.8)
    p.add_argument("--max-delay", type=float, default=2.0)
    p.add_argument("--max-edges", type=int, default=12)
    p.add_argument("--no-portraits", action="store_true")
    p.add_argument("--template-size", type=int, default=96)
    p.add_argument("--no-official-icons", dest="prefer_official_icons", action="store_false",
                   help="keep Fandom icons instead of Moonton's official ones")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
