"""Extrait un livre de la Bible Crampon 1923 depuis le HTML rendu de Wikisource.

Usage : python3 extract_wikisource.py page.html CODE "Nom" > content/text/CODE.json
Le HTML s'obtient avec :
  https://fr.wikisource.org/w/index.php?title=Bible_Crampon_1923/<Livre>&action=render
"""
import json
import re
import sys

from bs4 import BeautifulSoup, NavigableString, Tag

VERSE_ID = re.compile(r"^(\d+)-(\d+)$")


def clean(s: str) -> str:
    s = s.replace("\xa0", " ").replace(" ", " ")
    s = re.sub(r"\s+", " ", s).strip()
    # pas d'espace avant la ponctuation fermante simple
    s = re.sub(r"\s+([,.])", r"\1", s)
    return s


def is_summary(el: Tag) -> bool:
    """Bloc éditorial (plan, résumé) sans numéro de verset : à ignorer.
    Un bloc sans verset en texte simple est une suite de verset (saut de page) : on le garde."""
    if el.find(id=VERSE_ID):
        return False
    text = el.get_text(" ", strip=True)
    if not text:
        return False
    if re.match(r"^(\d+|[A-Z]|[IVX]+)\.\s", text) or "Chap." in text[:40]:
        return True
    first = next((c for c in el.children if isinstance(c, Tag) and c.name != "br"
                  or (isinstance(c, NavigableString) and c.strip())), None)
    return isinstance(first, Tag) and (first.name == "i" or "sc" in (first.get("class") or []))


def main(paths, code, name):
    """paths : un fichier HTML, ou plusieurs séparés par des virgules (livre découpé en pages)."""
    verses = []
    for path in paths.split(","):
        verses += extract(path)
    output(verses, code, name)


def extract(path):
    soup = BeautifulSoup(open(path, encoding="utf-8").read(), "lxml")
    # Un livre peut être découpé en plusieurs pages (Psaumes) : on prend, dans l'ordre,
    # tous les blocs de texte qui contiennent des versets.
    roots = [d for d in soup.find_all("div", class_="prp-pages-output") if d.find(id=VERSE_ID)]

    # notes de bas de page : id -> texte
    notes = {}
    for li in soup.select("ol.references > li"):
        nid = li.get("id", "").replace("cite_note-", "")
        txt = li.select_one(".reference-text")
        if txt:
            notes[nid] = clean(txt.get_text(" "))

    verses = []  # dicts {c, v, t(list), h, notes}
    cur = None
    pending_heading = []

    def walk(node):
        nonlocal cur
        for el in node.children:
            if isinstance(el, NavigableString):
                if cur is not None and el.__class__.__name__ == "NavigableString":
                    cur["t"].append(str(el))
                continue
            if not isinstance(el, Tag):
                continue
            cls = el.get("class") or []
            if el.name in ("style", "script", "ol"):
                continue
            if el.name in ("h1", "h2", "h3", "h4", "h5", "h6"):
                txt = clean(el.get_text(" "))
                if el.name != "h2" and txt:
                    pending_heading.append(txt)
                continue
            if any(c.startswith("alinea") for c in cls) or "pagenum" in cls or "verset-num" in cls:
                continue
            if el.name == "sup" and "reference" in cls:
                a = el.find("a")
                if cur is not None and a and a.get("href", "").startswith("#cite_note-"):
                    nid = a["href"].replace("#cite_note-", "")
                    if nid in notes:
                        cur["notes"].append(notes[nid])
                continue
            if el.name == "div" and el.find("a", href="#Sommaire") and not el.find(id=VERSE_ID):
                continue
            if el.name in ("p", "div") and is_summary(el):
                continue
            m = VERSE_ID.match(el.get("id", "") or "")
            # id sans chapitre (« 3 » au lieu de « 6-3 ») : on garde le chapitre en cours
            bare = re.match(r"^(\d+)$", el.get("id", "") or "")
            if bare and cur is not None and el.find("sup", class_="verset-num"):
                m = re.match(r"^(\d+)-(\d+)$", f"{cur['c']}-{bare.group(1)}")
            if m:
                cur = {"c": int(m.group(1)), "v": int(m.group(2)), "t": [], "notes": []}
                if pending_heading:
                    cur["h"] = " — ".join(pending_heading)
                    pending_heading.clear()
                verses.append(cur)
                walk(el)  # contient seulement le numéro (ignoré)
                continue
            if el.name == "br":
                if cur is not None:
                    cur["t"].append(" ")
                continue
            walk(el)

    for root in roots:
        walk(root)
    return verses


INLINE_NUM = r"(?:(?<=\s)|(?<=^)|(?<=[.;:,!?»]))({n})(?=\s?[«A-ZÀ-ÖØ-Þa-zß-öø-ÿ(])"


def repair(chapters, code):
    """Corrige les erreurs de balisage de Wikisource (numéros mal placés, versets fusionnés)."""
    log = lambda msg: print(f"[{code}] {msg}", file=sys.stderr)
    # 1. Verset hors séquence : on le rend au chapitre auquel il manque (ex. 5-25 au lieu de 4-25).
    for c in sorted(chapters):
        vs = chapters[c]
        i = 0
        while i < len(vs):
            v = vs[i]["v"]
            expected = vs[i - 1]["v"] + 1 if i > 0 else 1
            if v != expected and not (i == 0 and v == 0):
                # chapitre où ce verset manque juste après le dernier
                def lacks(k):
                    nums = [x["v"] for x in chapters[k]]
                    return bool(nums) and v not in nums and min(nums) < v <= max(nums) + 1
                homes = [k for k in chapters if k != c and lacks(k)]
                if len(homes) > 1:
                    # départage : le chapitre qui partage le plus de mots rares avec ce verset
                    words = lambda t: {w for w in re.findall(r"\w{5,}", t.lower())}
                    mine = words(vs[i]["t"])
                    scored = sorted(((len(mine & words(" ".join(x["t"] for x in chapters[k]))), k) for k in homes), reverse=True)
                    if scored[0][0] > scored[1][0]:
                        homes = [scored[0][1]]
                nxt = vs[i + 1]["v"] if i + 1 < len(vs) else None
                if len(homes) == 1 and (nxt is None or nxt != v + 1):
                    chapters[homes[0]].append(vs.pop(i))
                    log(f"{c}:{v} déplacé vers {homes[0]}:{v}")
                    continue
            i += 1
    for c in chapters:
        chapters[c].sort(key=lambda x: x["v"])
    # 2. Versets fusionnés dans le précédent (« … 10Réduis-les … ») : on les redécoupe.
    for c in sorted(chapters):
        vs = chapters[c]
        i = 1
        while i < len(vs):
            prev, cur = vs[i - 1], vs[i]
            missing = list(range(prev["v"] + 1, cur["v"]))
            if missing:
                text, parts = prev["t"], []
                for n in missing:
                    m = re.search(INLINE_NUM.format(n=n), text)
                    if not m:
                        break
                    parts.append((n, m.start(), m.end()))
                    text = text  # positions croissantes vérifiées plus bas
                ok = len(parts) == len(missing) and all(a[2] <= b[1] for a, b in zip(parts, parts[1:]))
                if ok:
                    t = prev["t"]
                    cuts = [(None, 0, 0)] + parts
                    pieces = []
                    for k, (n, s0, e0) in enumerate(cuts):
                        end = cuts[k + 1][1] if k + 1 < len(cuts) else len(t)
                        pieces.append((n, t[e0:end].strip()))
                    prev["t"] = pieces[0][1]
                    for k, (n, txt) in enumerate(pieces[1:]):
                        vs.insert(i + k, {"v": n, "t": txt})
                    log(f"{c}:{prev['v']} redécoupé en {missing}")
                    i += len(missing)
                else:
                    log(f"{c}: versets {missing} introuvables (laissés dans {c}:{prev['v']})")
            i += 1
    for c in chapters:
        chapters[c].sort(key=lambda x: x["v"])
    return chapters


def output(verses, code, name):
    chapters = {}
    for v in verses:
        text = clean("".join(v["t"]))
        item = {"v": v["v"], "t": text}
        if v.get("h"):
            item["h"] = v["h"]
        if v["notes"]:
            item["n"] = v["notes"]
        chapters.setdefault(v["c"], []).append(item)
    chapters = repair(chapters, code)

    out = {
        "code": code,
        "name": name,
        "source": "Bible Crampon 1923 — Wikisource (domaine public)",
        "chapters": [{"c": c, "verses": vs} for c, vs in sorted(chapters.items())],
    }
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:4])
