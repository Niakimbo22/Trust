"""Régénère content/books.json à partir du manifeste et des fichiers présents dans content/."""
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
    })
path = os.path.join(ROOT, "content", "books.json")
old = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"version": 0}
out = {"version": old.get("version", 0) + 1, "books": books}
if old.get("books") == books:
    out["version"] = old["version"]
json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"books.json : {len(books)} livres, {sum(1 for b in books if b['timings'])} avec timings, version {out['version']}")
