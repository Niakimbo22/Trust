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

Les livres en plusieurs fichiers audio (Genèse, Psaumes…) sont traités sur une ligne de
temps globale : fichier 2 = durée du fichier 1 + position. Les temps écrits sont globaux ;
l'app retrouve (fichier, position) grâce aux durées listées dans "audio".

Usage : python3 align_timings.py CODE text.json words.json bible_manifest.json > timings.json
  words.json : {"files": [[[début, fin, "mot"], ...], ...]}  (un tableau par fichier audio)
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


def load_words(words_path, audio):
    data = json.load(open(words_path, encoding="utf-8"))
    per_file = data["files"] if isinstance(data, dict) else [data]
    assert len(per_file) == len(audio), "un tableau de mots par fichier audio"
    words, offset = [], 0.0
    for fw, a in zip(per_file, audio):
        words += [[offset + s, offset + e, w] for s, e, w in fw]
        offset += a["duration"]
    return words, offset


def reading_order(canonical, spec):
    """Ordre de lecture : celui des numéros, sauf si le manifeste donne « order »
    (ex. Esther : ["11:2-12:6", "1:1-3:13", "13:1-7", ...]). Chaque verset doit y figurer une fois."""
    if not spec:
        return canonical
    pos = {k: i for i, k in enumerate(canonical)}
    out = []
    for item in spec:
        a, _, b = item.partition("-")
        ca, va = map(int, a.split(":"))
        if ":" in b:
            cb, vb = map(int, b.split(":"))
        else:
            cb, vb = ca, int(b) if b else va
        out += canonical[pos[(ca, va)]:pos[(cb, vb)] + 1]
    assert sorted(out) == sorted(canonical), "order : versets manquants ou en double"
    return out


def main(code, text_path, words_path, manifest_path):
    book = json.load(open(text_path, encoding="utf-8"))
    entry = next(b for b in json.load(open(manifest_path, encoding="utf-8"))["books"] if b["code"] == code)
    audio = entry["audio"]
    words, duration = load_words(words_path, audio)

    # Référence : (chapitre, verset) de chaque jeton, dans l'ordre de lecture de l'audio.
    texts = {(ch["c"], v["v"]): v["t"] for ch in book["chapters"] for v in ch["verses"]}
    verse_keys = reading_order(list(texts), entry.get("order"))
    ref, ref_verse = [], []
    for vi, key in enumerate(verse_keys):
        for tok in norm_tokens(texts[key]):
            ref.append(tok)
            ref_verse.append(vi)

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

    # Passe 1 : alignement global (texte et audio dans le même ordre).
    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    ref_time = [None] * len(ref)
    hyp_used = [False] * len(hyp)
    for a, b, n in sm.get_matching_blocks():
        for k in range(n):
            ref_time[a + k] = hyp_t[b + k]
            hyp_used[b + k] = True

    verse_toks = {}
    for idx, vi in enumerate(ref_verse):
        verse_toks.setdefault(vi, []).append(idx)

    def verse_ratio(vi):
        toks = verse_toks.get(vi, [])
        return sum(1 for i in toks if ref_time[i]) / max(1, len(toks))

    # Passe 2 : passages lus ailleurs dans l'audio (ex. ajouts grecs d'Esther lus dans le récit).
    # Une suite de versets non retrouvés est recherchée dans les zones d'audio restées libres.
    segments = []  # (début, fin) d'indices de jetons de référence placés hors de l'ordre global
    runs, cur = [], []
    for vi in range(len(verse_keys)):
        if verse_ratio(vi) < 0.2:
            cur.append(vi)
        elif cur:
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)
    gaps, g = [], None
    for j, used in enumerate(hyp_used):
        if not used and g is None:
            g = j
        elif used and g is not None:
            if j - g >= 30:
                gaps.append((g, j))
            g = None
    if g is not None and len(hyp) - g >= 30:
        gaps.append((g, len(hyp)))
    for run in runs:
        a0, a1 = verse_toks[run[0]][0], verse_toks[run[-1]][-1] + 1
        if a1 - a0 < 30:
            continue
        best = None
        for (b0, b1) in gaps:
            m = difflib.SequenceMatcher(a=ref[a0:a1], b=hyp[b0:b1], autojunk=False)
            blocks = [blk for blk in m.get_matching_blocks() if blk.size >= 2]
            score = sum(blk.size for blk in blocks)
            if best is None or score > best[0]:
                best = (score, b0, blocks)
        if best and best[0] >= 0.3 * (a1 - a0):
            _, b0, blocks = best
            for blk in blocks:
                for k in range(blk.size):
                    if not hyp_used[b0 + blk.b + k]:
                        ref_time[a0 + blk.a + k] = hyp_t[b0 + blk.b + k]
                        hyp_used[b0 + blk.b + k] = True
            segments.append((a0, a1))
            print(f"passage relocalisé : {verse_keys[run[0]]} → {verse_keys[run[-1]]}", file=sys.stderr)

    matched = sum(1 for x in ref_time if x)

    # Interpolation des jetons non alignés, segment par segment (chaque segment est dans l'ordre de l'audio).
    bounds = sorted({0, len(ref)} | {x for seg in segments for x in seg})
    starts = [None] * len(ref)
    for s0, s1 in zip(bounds, bounds[1:]):
        known = [i for i in range(s0, s1) if ref_time[i]]
        if not known:
            continue
        for i in known:
            starts[i] = ref_time[i][0]
        for j in range(len(known) - 1):
            a, b = known[j], known[j + 1]
            if b - a > 1:
                ta, tb = ref_time[a][1], ref_time[b][0]
                for i in range(a + 1, b):
                    starts[i] = ta + (tb - ta) * (i - a) / (b - a)
        for i in range(s0, known[0]):
            starts[i] = ref_time[known[0]][0]
        for i in range(known[-1] + 1, s1):
            starts[i] = ref_time[known[-1]][1]

    # Fin du mot précédent (pour avancer le début dans le silence).
    prev_end = [None] * len(ref)
    for i in range(1, len(ref)):
        prev_end[i] = ref_time[i - 1][1] if ref_time[i - 1] else starts[i]

    verse_start, verse_quality = {}, {}
    for vi in range(len(verse_keys)):
        idx = verse_toks[vi][0]
        s = starts[idx]
        if s is None:
            continue
        pe = prev_end[idx]
        if ref_time[idx] and pe is not None and pe < s:
            s = max(pe, s - LEAD)
        verse_start[vi] = s
        verse_quality[vi] = verse_ratio(vi)

    # Chapitres absents de l'audio (ex. Psaumes 90 à 106 manquent chez Mission Web) : retirés.
    by_chapter = {}
    for vi, (c, v) in enumerate(verse_keys):
        by_chapter.setdefault(c, []).append(vi)
    by_chapter = {c: sorted(vis, key=lambda vi: verse_keys[vi][1]) for c, vis in sorted(by_chapter.items())}
    missing = []
    for c, vis in by_chapter.items():
        toks = [i for vi in vis for i in verse_toks[vi]]
        if sum(1 for i in toks if ref_time[i]) / max(1, len(toks)) < 0.15 or any(vi not in verse_start for vi in vis):
            missing.append(c)
    if missing:
        print(f"chapitres sans audio : {missing}", file=sys.stderr)

    # Versets écrasés au même instant (passages répétés mot pour mot, ex. Nombres 7) :
    # on les répartit entre les voisins, au prorata de leur longueur.
    order = sorted((vi for vi in verse_start if verse_keys[vi][0] not in missing), key=lambda vi: (verse_start[vi], vi))
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and verse_start[order[j + 1]] - verse_start[order[i]] < 0.3:
            j += 1
        if j > i:
            lo = verse_start[order[i]]
            hi = verse_start[order[j + 1]] if j + 1 < len(order) else duration
            weights = [len(verse_toks[vi]) for vi in order[i:j + 1]]
            total, acc = sum(weights), 0
            for k, vi in enumerate(order[i:j + 1]):
                verse_start[vi] = lo + (hi - lo) * acc / total
                acc += weights[k]
        i = j + 1

    # Débuts de chapitre : l'annonce (« Chapitre N », parfois juste « N ») est dite
    # dans le blanc entre la fin du chapitre précédent et le verset 1.
    chapter_start = {}
    for c, vis in by_chapter.items():
        if c in missing:
            continue
        vi = vis[0]
        vs = verse_start[vi]
        idx = verse_toks[vi][0]
        if idx == 0:
            cands = [s for (s, _), tok in zip(hyp_t, hyp) if tok == "chapitre" and vs - 8 < s <= vs]
            floor = 0.0
        else:
            prev = ref_time[idx - 1][1] if ref_time[idx - 1] else (starts[idx - 1] or 0.0)
            floor = prev if prev < vs else max(0.0, vs - 8)
            cands = [s for (s, _) in hyp_t if floor < s < vs - 0.05]
        cs = (min(cands) - LEAD) if cands else vs
        chapter_start[c] = round(min(vs, max(floor, cs)), 2)

    chapters = []
    for c, vis in by_chapter.items():
        if c in missing:
            continue
        chapters.append({"c": c, "start": chapter_start[c], "verses": [round(verse_start[vi], 2) for vi in vis]})

    out = {
        "code": code,
        "version": 1,
        "audio": audio,
        "duration": round(duration, 2),
        "chapters": chapters,
    }
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))

    # Rapport de qualité sur stderr.
    weak = [(verse_keys[vi], round(q, 2)) for vi, q in verse_quality.items() if q < 0.5]
    print(f"jetons alignés : {matched}/{len(ref)} ({matched / len(ref):.1%})", file=sys.stderr)
    print(f"versets faibles (<50% de mots reconnus) : {len(weak)} {weak[:30]}", file=sys.stderr)
    times = sorted(verse_start[vi] for vi in verse_start if verse_keys[vi][0] not in missing)
    nonmono = [t for a, t in zip(times, times[1:]) if t - a < 0.05]
    print(f"versets de durée quasi nulle : {len(nonmono)}", file=sys.stderr)
    report = {"code": code, "aligned": round(matched / len(ref), 4), "weak": len(weak),
              "verses": len(verse_keys), "nonmonotonic": len(nonmono), "missing_chapters": missing}
    print("REPORT " + json.dumps(report), file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:5])
