"""Régénère content/books.json à partir du manifeste et des fichiers présents dans content/."""
import hashlib
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
manifest = json.load(open(os.path.join(ROOT, "tools", "bible_manifest.json"), encoding="utf-8"))
books = []
for b in manifest["books"]:
    text = os.path.join(ROOT, "content", "text", f"{b['code']}.json")
    if not os.path.exists(text):
        continue
    t = json.load(open(text, encoding="utf-8"))
    timings = os.path.join(ROOT, "content", "timings", f"{b['code']}.json")
    # Empreinte du texte et des timings : l'app ne retélécharge un livre que si elle change
    # (et la version de books.json change avec elle).
    digest = hashlib.sha256()
    for f in (text, timings):
        if os.path.exists(f):
            digest.update(open(f, "rb").read())
    books.append({
        "code": b["code"],
        "name": b["name"],
        "short": b["short"],
        "testament": b["testament"],
        "chapters": len(t["chapters"]),
        "verses": sum(len(c["verses"]) for c in t["chapters"]),
        "text": f"text/{b['code']}.json",
        "timings": f"timings/{b['code']}.json" if os.path.exists(timings) else None,
        "audio": b["audio"],
        "rev": digest.hexdigest()[:12],
    })
path = os.path.join(ROOT, "content", "books.json")
old = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"version": 0}
out = {"version": old.get("version", 0) + 1, "books": books}
if old.get("books") == books:
    out["version"] = old["version"]
json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"books.json : {len(books)} livres, {sum(1 for b in books if b['timings'])} avec timings, version {out['version']}")
