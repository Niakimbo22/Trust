"""Calcule le début de chaque verset dans l'audio à partir d'une transcription horodatée.

Entrées :
  - content/text/<CODE>.json  (texte de référence, voir extract_wikisource.py)
  - words.json : [[début, fin, "mot"], ...] produit par transcribe.py (faster-whisper)
Sortie : content/timings/<CODE>.json

Principe : on aligne la suite de mots du texte de référence avec la suite de mots
reconnus (difflib). Chaque mot du texte reçoit l'horodatage du mot reconnu
correspondant ; les mots non reconnus sont interpolés entre leurs voisins.
Le début d'un verset = début de son premier mot, légèrement avancé dans le silence
qui précède (pour ne pas couper la première syllabe).

Usage : python3 align_timings.py CODE text.json words.json audio_url duree_s > timings.json
"""
import difflib
import json
import re
import sys
import unicodedata

LEAD = 0.25  # secondes d'avance max dans le silence avant un verset


def norm_tokens(text: str):
    t = unicodedata.normalize("NFD", text.lower())
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn")
    t = t.replace("œ", "oe").replace("æ", "ae")
    t = re.sub(r"[’'`\-]", " ", t)
    t = re.sub(r"[^a-z0-9 ]", " ", t)
    return t.split()


def main(code, text_path, words_path, audio_url, duration):
    book = json.load(open(text_path, encoding="utf-8"))
    words = json.load(open(words_path, encoding="utf-8"))

    # Référence : (chapitre, verset) de chaque jeton.
    ref, ref_verse, verse_keys = [], [], []
    for ch in book["chapters"]:
        for v in ch["verses"]:
            key = (ch["c"], v["v"])
            verse_keys.append(key)
            for tok in norm_tokens(v["t"]):
                ref.append(tok)
                ref_verse.append(len(verse_keys) - 1)

    # Hypothèse : jetons reconnus avec leur temps (un mot whisper peut donner plusieurs jetons).
    hyp, hyp_t = [], []
    for start, end, w in words:
        toks = norm_tokens(w)
        for i, tok in enumerate(toks):
            # répartit la durée du mot entre ses jetons
            s = start + (end - start) * i / len(toks)
            e = start + (end - start) * (i + 1) / len(toks)
            hyp.append(tok)
            hyp_t.append((s, e))

    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    ref_time = [None] * len(ref)
    for a, b, n in sm.get_matching_blocks():
        for k in range(n):
            ref_time[a + k] = hyp_t[b + k]

    matched = sum(1 for x in ref_time if x)

    # Interpolation des jetons non alignés.
    known = [i for i, x in enumerate(ref_time) if x]
    starts = [None] * len(ref)
    for i in known:
        starts[i] = ref_time[i][0]
    for j in range(len(known) - 1):
        a, b = known[j], known[j + 1]
        if b - a > 1:
            ta, tb = ref_time[a][1], ref_time[b][0]
            for i in range(a + 1, b):
                starts[i] = ta + (tb - ta) * (i - a) / (b - a)
    first, last = known[0], known[-1]
    for i in range(0, first):
        starts[i] = ref_time[first][0]
    for i in range(last + 1, len(ref)):
        starts[i] = ref_time[last][1]

    # Fin du mot précédent (pour avancer le début dans le silence).
    prev_end = [None] * len(ref)
    for i in range(1, len(ref)):
        prev_end[i] = ref_time[i - 1][1] if ref_time[i - 1] else starts[i]

    verse_start, verse_quality = {}, {}
    for idx in range(len(ref)):
        vi = ref_verse[idx]
        if vi not in verse_start:
            s = starts[idx]
            pe = prev_end[idx]
            if ref_time[idx] and pe is not None and pe < s:
                s = max(pe, s - LEAD)
            verse_start[vi] = s
    for vi in range(len(verse_keys)):
        toks = [i for i in range(len(ref)) if ref_verse[i] == vi]
        verse_quality[vi] = sum(1 for i in toks if ref_time[i]) / max(1, len(toks))

    # Débuts de chapitre : l'annonce (« Chapitre N », parfois juste « N ») est dite
    # dans le blanc entre la fin du chapitre précédent et le verset 1.
    first_tok = {}
    for idx, vi in enumerate(ref_verse):
        first_tok.setdefault(vi, idx)
    chapter_start = {}
    for vi, (c, v) in enumerate(verse_keys):
        if v != 1:
            continue
        vs = verse_start[vi]
        idx = first_tok[vi]
        if idx == 0:
            cands = [s for (s, _), tok in zip(hyp_t, hyp) if tok == "chapitre" and vs - 8 < s <= vs]
            floor = 0.0
        else:
            floor = ref_time[idx - 1][1] if ref_time[idx - 1] else starts[idx - 1]
            cands = [s for (s, _) in hyp_t if floor < s < vs - 0.05]
        cs = (min(cands) - LEAD) if cands else vs
        chapter_start[c] = round(max(floor, cs), 2)

    chapters = []
    for vi, (c, v) in enumerate(verse_keys):
        if v == 1:
            chapters.append({"c": c, "start": chapter_start[c], "verses": []})
        chapters[-1]["verses"].append(round(verse_start[vi], 2))

    out = {
        "code": code,
        "version": 1,
        "audio": audio_url,
        "duration": round(float(duration), 2),
        "chapters": chapters,
    }
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))

    # Rapport de qualité sur stderr.
    weak = [(verse_keys[vi], round(q, 2)) for vi, q in verse_quality.items() if q < 0.5]
    print(f"jetons alignés : {matched}/{len(ref)} ({matched / len(ref):.1%})", file=sys.stderr)
    print(f"versets faibles (<50% de mots reconnus) : {len(weak)} {weak[:30]}", file=sys.stderr)
    seq = [verse_start[i] for i in range(len(verse_keys))]
    nonmono = [verse_keys[i] for i in range(1, len(seq)) if seq[i] <= seq[i - 1]]
    print(f"débuts non croissants : {nonmono}", file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:6])
