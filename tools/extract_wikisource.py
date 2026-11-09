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
    if re.match(r"^\d+\.\s", text):
        return True
    first = next((c for c in el.children if isinstance(c, Tag) and c.name != "br"
                  or (isinstance(c, NavigableString) and c.strip())), None)
    return isinstance(first, Tag) and (first.name == "i" or "sc" in (first.get("class") or []))


def main(path, code, name):
    soup = BeautifulSoup(open(path, encoding="utf-8").read(), "lxml")
    root = soup.find(id="1-1").find_parent("div", class_="prp-pages-output")

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
            if el.name == "div" and el.find("a", href="#Sommaire"):
                continue
            if el.name in ("p", "div") and is_summary(el):
                continue
            m = VERSE_ID.match(el.get("id", "") or "")
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

    walk(root)

    chapters = {}
    for v in verses:
        text = clean("".join(v["t"]))
        item = {"v": v["v"], "t": text}
        if v.get("h"):
            item["h"] = v["h"]
        if v["notes"]:
            item["n"] = v["notes"]
        chapters.setdefault(v["c"], []).append(item)

    out = {
        "code": code,
        "name": name,
        "source": "Bible Crampon 1923 — Wikisource (domaine public)",
        "chapters": [{"c": c, "verses": vs} for c, vs in sorted(chapters.items())],
    }
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:4])
