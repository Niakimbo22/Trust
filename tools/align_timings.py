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

Quand le texte lu s'écarte du texte de référence (certains livres du Nouveau Testament
sont lus dans une édition révisée de la Crampon : mêmes versets, autres mots), le premier
mot d'un verset n'est souvent pas retrouvé. Deux aides supplémentaires :
  - mots « presque pareils » (faute de transcription, accord) acceptés dans les trous ;
  - le début du verset est alors cherché là où le lecteur marque la coupure : silence et
    ponctuation de la transcription, autour de la place attendue d'après le nombre de mots.
    L'annonce « Chapitre N » reste avant le verset 1.

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

# Choix du début d'un verset dont le premier mot n'est pas retrouvé (voir choose_boundaries).
W_PAUSE = 1.0     # poids du silence avant le mot (plafonné à PAUSE_CAP secondes)
PAUSE_CAP = 1.0
W_STRONG = 0.6    # le mot d'avant finit par . ! ? ; : »
W_COMMA = 0.15    # le mot d'avant finit par une virgule
W_PRIOR = 1.6     # éloignement de la place attendue, en mots, rapporté à PRIOR_FLOOR + PRIOR_REL × taille du trou
PRIOR_FLOOR = 8.0
PRIOR_REL = 0.25
MAX_GAP_CELLS = 400_000  # au-delà, un trou est trop grand pour l'alignement fin

# Trou de transcription : un « silence » aussi long entre deux mots transcrits est en réalité un passage
# que la transcription a sauté (les vraies pauses, même entre chapitres, restent sous 3 s).
HOLE_MIN = 3.0
HOLE_EDGE = 0.3   # pause supposée au bord d'un trou
W_HOLE = 0.8      # dans un trou on n'entend rien : indice neutre, ni silence ni ponctuation
W_OWN = 0.8       # le premier mot du verset est retrouvé à cet endroit (mot isolé : indice, pas certitude)
W_OWN_SHORT = 0.3  # … mais c'est un tout petit mot (« et », « des ») : simple coïncidence possible
W_ANNOUNCE = 1.0  # juste après l'annonce « Chapitre N » : c'est là que commence le verset 1
W_NEAR = 0.5      # le 2e ou 3e mot du verset est retrouvé : le début est juste avant lui
# Annonce lue avant le verset 1 : « Chapitre 3 », « Psaume 86, Vulgate 85 » (« somme » = psaume mal entendu)
ANNOUNCE_WORDS = {"chapitre", "psaume", "somme"}

MIN_VERSE = 0.35  # durée minimale entre deux débuts de verset placés

NUMBER_WORDS = set("""un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize
vingt trente quarante cinquante soixante cent premier""".split())

def norm_tokens(text: str):
    t = unicodedata.normalize("NFD", text.lower())
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn")
    t = t.replace("œ", "oe").replace("æ", "ae")
    t = re.sub(r"[’'`\-]", " ", t)
    t = re.sub(r"[^a-z0-9 ]", " ", t)
    return t.split()


def punct_strength(word: str) -> int:
    """Ponctuation à la fin d'un mot transcrit : 2 = fin de phrase, 1 = virgule, 0 = rien."""
    last = word.strip()[-1:]
    return 2 if last and last in ".!?;:»…" else 1 if last == "," else 0


def similar(a: str, b: str) -> bool:
    """Deux jetons presque identiques : faute de transcription ou accord (impies/impis, exhorte/exorte)."""
    if a == b:
        return True
    if len(a) < 4 or len(b) < 4 or abs(len(a) - len(b)) > 2 or a[:2] != b[:2]:
        return False
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.75


def fuzzy_pairs(r, h):
    """Plus longue suite commune de jetons « presque pareils » entre deux petites listes : [(i, j), ...]."""
    n, m = len(r), len(h)
    if n == 0 or m == 0 or n * m > MAX_GAP_CELLS:
        return []
    sim = [[similar(r[i], h[j]) for j in range(m)] for i in range(n)]
    if not any(any(row) for row in sim):
        return []
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            dp[i][j] = dp[i + 1][j + 1] + 1 if sim[i][j] else max(dp[i + 1][j], dp[i][j + 1])
    out, i, j = [], 0, 0
    while i < n and j < m:
        if sim[i][j] and dp[i][j] == dp[i + 1][j + 1] + 1:
            out.append((i, j))
            i, j = i + 1, j + 1
        elif dp[i + 1][j] >= dp[i][j + 1]:
            i += 1
        else:
            j += 1
    return out


def is_number(tok: str) -> bool:
    """Numéro de chapitre tel que la transcription l'écrit : 12, xii, douze…"""
    # chiffres romains bien formés seulement (« il » ou « ci » n'en sont pas)
    return (tok.isdigit() or tok in NUMBER_WORDS
            or re.fullmatch(r"(?=[ivxlc])c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})", tok) is not None)


def choose_boundaries(cands, expected, bonus=None):
    """Place des débuts de verset parmi les endroits possibles d'un trou du texte.

    cands : [(position, indice), ...] croissants. La position est comptée en mots le long de l'audio
    (un trou de transcription compte pour les mots qu'il a dû contenir) ; l'indice dit à quel point
    l'endroit ressemble à une reprise du lecteur (silence, ponctuation juste avant).
    expected : place attendue de chaque début (dans l'ordre), d'après le nombre de mots du texte.
    bonus : par début, {position: indice supplémentaire} (premier mot retrouvé là, fin de l'annonce).
    Retourne, pour chaque début, le rang du candidat retenu : celui qui ressemble le plus à une
    reprise sans trop s'écarter de la place attendue, en gardant l'ordre des versets.
    """
    span = max(1.0, PRIOR_FLOOR + PRIOR_REL * (cands[-1][0] - cands[0][0]))

    def score(c, t):
        extra = bonus[t].get(cands[c][0], 0.0) if bonus else 0.0
        return cands[c][1] + extra - W_PRIOR * abs(cands[c][0] - expected[t]) / span

    # Programmation dynamique : best[c] = meilleur total en plaçant le début courant sur le candidat c
    # (deux débuts ne partagent le même endroit que s'il n'y a pas assez de candidats).
    n = len(cands)
    strict = n >= len(expected)
    best = [score(c, 0) for c in range(n)]
    back = []
    for t in range(1, len(expected)):
        prev_best, prev_arg, nxt, arg = float("-inf"), -1, [], []
        for c in range(n):
            upto = c - 1 if strict else c
            if upto >= 0 and best[upto] > prev_best:
                prev_best, prev_arg = best[upto], upto
            nxt.append(prev_best + score(c, t) if prev_arg >= 0 else float("-inf"))
            arg.append(prev_arg)
        back.append(arg)
        best = nxt
    c = max(range(n), key=lambda x: best[x])
    chosen = [c]
    for arg in reversed(back):
        c = arg[c]
        chosen.append(c)
    return list(reversed(chosen))


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
    hyp, hyp_t, hyp_punct = [], [], []
    for start, end, w in words:
        toks = norm_tokens(w)
        for i, tok in enumerate(toks):
            # répartit la durée du mot entre ses jetons
            s = start + (end - start) * i / len(toks)
            e = start + (end - start) * (i + 1) / len(toks)
            hyp.append(tok)
            hyp_t.append((s, e))
            hyp_punct.append(punct_strength(w) if i == len(toks) - 1 else 0)
    # silence avant chaque jeton
    hyp_pause = [0.0] + [max(0.0, hyp_t[j][0] - hyp_t[j - 1][1]) for j in range(1, len(hyp))]
    # Débit du lecteur (mots par seconde, pauses courtes comprises) et « axe en mots » le long de l'audio :
    # chaque mot transcrit avance de 1 ; un trou de transcription avance des mots qu'il a dû contenir.
    spoken = sum(e - s for s, e in hyp_t) + sum(g for g in hyp_pause if g < 1.0)
    rate = len(hyp) / spoken if spoken > 0 else 2.7
    hole_words = [max(1, round((g - 2 * HOLE_EDGE) * rate)) if g >= HOLE_MIN else 0 for g in hyp_pause]
    hyp_pos, acc = [], -1
    for j in range(len(hyp)):
        acc += 1 + hole_words[j]
        hyp_pos.append(acc)

    def candidates(j_from, j_to):
        """Endroits où un verset peut commencer, du mot j_from au mot j_to : (position, indice, temps, mot)."""
        out = []
        for j in range(j_from, j_to + 1):
            before = hyp_punct[j - 1] if j > 0 else 0
            mark = W_STRONG if before == 2 else W_COMMA if before == 1 else 0.0
            if hole_words[j]:
                # dans le trou : un endroit par mot manquant, à intervalles réguliers
                t0 = hyp_t[j - 1][1] + HOLE_EDGE
                t1 = hyp_t[j][0] - HOLE_EDGE
                for u in range(hole_words[j]):
                    t = t0 + (t1 - t0) * u / hole_words[j]
                    out.append((hyp_pos[j] - hole_words[j] + u, W_HOLE + (mark if u == 0 else 0.0), t, None))
                out.append((hyp_pos[j], W_HOLE, hyp_t[j][0], j))
            else:
                out.append((hyp_pos[j], W_PAUSE * min(hyp_pause[j], PAUSE_CAP) + mark, hyp_t[j][0], j))
        return out

    # Passe 1 : alignement global (texte et audio dans le même ordre).
    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    ref_time = [None] * len(ref)
    ref_hyp = [None] * len(ref)  # indice du jeton reconnu correspondant
    hyp_used = [False] * len(hyp)
    for a, b, n in sm.get_matching_blocks():
        for k in range(n):
            ref_time[a + k] = hyp_t[b + k]
            ref_hyp[a + k] = b + k
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
                        ref_hyp[a0 + blk.a + k] = b0 + blk.b + k
                        hyp_used[b0 + blk.b + k] = True
            segments.append((a0, a1))
            print(f"passage relocalisé : {verse_keys[run[0]]} → {verse_keys[run[-1]]}", file=sys.stderr)

    matched = sum(1 for x in ref_time if x)
    exact = [x is not None for x in ref_hyp]  # retrouvé à l'identique (passes 1 et 2)

    # Segments dans l'ordre de l'audio (un seul, sauf passages relocalisés).
    bounds = sorted({0, len(ref)} | {x for seg in segments for x in seg})

    # Passe 3 : dans chaque trou entre deux mots retrouvés, on accepte les mots « presque pareils ».
    fuzzy = 0
    for s0, s1 in zip(bounds, bounds[1:]):
        known = [i for i in range(s0, s1) if ref_hyp[i] is not None]
        for a, b in zip(known, known[1:]):
            ja, jb = ref_hyp[a], ref_hyp[b]
            if b - a < 2 or jb - ja < 2:
                continue
            for di, dj in fuzzy_pairs(ref[a + 1:b], hyp[ja + 1:jb]):
                i, j = a + 1 + di, ja + 1 + dj
                if not hyp_used[j]:
                    ref_time[i], ref_hyp[i], hyp_used[j] = hyp_t[j], j, True
                    fuzzy += 1

    # Interpolation des jetons non alignés, segment par segment (chaque segment est dans l'ordre de l'audio).
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

    # Débuts de verset dont le premier mot n'est pas sûr : cherchés parmi les mots transcrits du trou.
    first_tok = {verse_toks[vi][0]: vi for vi in range(len(verse_keys))}
    chapter_firsts = set()
    seen_chapters = set()
    for vi, (c, _) in enumerate(verse_keys):
        if c not in seen_chapters:
            seen_chapters.add(c)
            chapter_firsts.add(vi)

    def confirmed(i):
        """Mot retrouvé (même approximativement) dont un voisin est retrouvé juste à côté dans l'audio."""
        j = ref_hyp[i]
        if j is None:
            return False
        return (i + 1 < len(ref) and ref_hyp[i + 1] == j + 1) or (i > 0 and ref_hyp[i - 1] == j - 1)

    # Repère solide : confirmé par un voisin, ou mot long retrouvé à l'identique. Un petit mot isolé
    # (« des », « et »…) retrouvé seul dans un passage qui diffère est souvent une coïncidence.
    strong = [confirmed(i) or (exact[i] and len(ref[i]) >= 6) for i in range(len(ref))]

    verse_place = {}  # verset → endroit choisi pour son début : (position, indice, temps, mot ou None)
    for s0, s1 in zip(bounds, bounds[1:]):
        solid = [i for i in range(s0, s1) if strong[i]]
        if not solid:
            continue
        # Un début est « à placer » si son premier mot n'est pas un repère solide. Son trou va du dernier
        # repère solide d'avant au premier repère solide à partir de lui.
        todo = [i for i in range(solid[0] + 1, solid[-1] + 1) if i in first_tok and not strong[i]]
        groups = {}
        for i in todo:
            a = i - 1
            while not strong[a]:
                a -= 1
            b = i
            while not strong[b]:
                b += 1
            if ref_hyp[b] <= ref_hyp[a]:
                continue
            groups.setdefault((a, b), []).append(i)
        for (a, b), firsts in groups.items():
            ja, jb = ref_hyp[a], ref_hyp[b]
            # Si le trou se termine sur le premier mot (sûr) d'un autre verset, cette place est prise.
            cands = candidates(ja + 1, jb - 1 if b in first_tok else jb)
            if not cands:
                continue  # rien de transcrit dans le trou (verset absent de l'audio) : on garde l'interpolation

            def expect(i, i_from, pos_from, i_to, pos_to):
                """Place attendue du mot i : au prorata des mots du texte entre deux repères sûrs."""
                return pos_from + 1 + (pos_to - pos_from - 1) * (i - i_from - 1) / max(1, i_to - i_from - 1)

            def own(i):
                """Un des premiers mots du verset est retrouvé (repère fragile) : indice à l'endroit qu'il désigne."""
                out = {}
                for d in range(3):
                    if i + d >= len(ref) or (d and i + d in first_tok):
                        break
                    j = ref_hyp[i + d]
                    if j is None or not ja < j - d <= jb:
                        continue
                    w = (W_OWN if exact[i] and len(ref[i]) >= 4 else W_OWN_SHORT) if d == 0 else W_NEAR
                    pos = hyp_pos[j] - d
                    out[pos] = max(out.get(pos, 0.0), w)
                # … ou un des derniers mots du verset d'avant : le début est juste après lui
                for d in range(1, 3):
                    k = i - d
                    if k <= a or (d > 1 and k + 1 in first_tok):
                        break
                    j = ref_hyp[k]
                    if j is None or not ja <= j < jb:
                        continue
                    pos = hyp_pos[j] + d
                    out[pos] = max(out.get(pos, 0.0), W_NEAR)
                return out

            # Annonce « Chapitre N » dans le trou : le verset 1 du chapitre commence juste après elle,
            # les versets d'avant finissent avant elle.
            split = next((t for t, i in enumerate(firsts) if first_tok[i] in chapter_firsts), None)
            ann = None
            if split is not None:
                ann = next((j for j in range(ja + 1, jb - 1) if hyp[j] in ANNOUNCE_WORDS and is_number(hyp[j + 1])), None)
                if ann is None:
                    continue  # annonce du chapitre introuvable : on ne devine pas, on garde l'interpolation
            after = []
            if ann is not None:

                def number_end(k):
                    """Fin du nombre qui commence au mot k : « 86 », « xvi » ou « trente huit »."""
                    if k >= jb or not is_number(hyp[k]):
                        return k - 1
                    if not hyp[k].isalpha() or hyp[k] not in NUMBER_WORDS:
                        return k  # chiffres (arabes ou romains) : un seul mot
                    while k + 1 < jb and k + 1 - ann < 6 and hyp[k + 1] in NUMBER_WORDS:
                        k += 1
                    return k

                ann_end = number_end(ann + 1)
                if ann_end + 1 < jb and hyp[ann_end + 1] in ("vulgate", "vulgates"):
                    ann_end = max(ann_end + 1, number_end(ann_end + 2))
                after = [c for c in cands if c[0] > hyp_pos[ann_end]]
                if not after:
                    continue
            if after:
                i_ch = firsts[split]
                before = [c for c in cands if c[0] < hyp_pos[ann]]
                chosen = []
                if split > 0:
                    exp = [expect(i, a, hyp_pos[ja], i_ch, hyp_pos[ann]) for i in firsts[:split]]
                    chosen = ([before[r] for r in choose_boundaries(before, exp, [own(i) for i in firsts[:split]])]
                              if before else [(hyp_pos[ann], 0.0, hyp_t[ann][0], ann)] * split)
                exp = [expect(i, i_ch - 1, hyp_pos[ann_end], b, hyp_pos[jb]) for i in firsts[split:]]
                extra = [own(i) for i in firsts[split:]]
                extra[0] = {**extra[0], after[0][0]: W_ANNOUNCE}
                chosen += [after[r] for r in choose_boundaries(after, exp, extra)]
            else:
                exp = [expect(i, a, hyp_pos[ja], b, hyp_pos[jb]) for i in firsts]
                chosen = [cands[r] for r in choose_boundaries(cands, exp, [own(i) for i in firsts])]
            # Garde-fou : jamais deux débuts collés (entre eux ou contre un début sûr voisin) — ils seraient
            # ensuite « répartis » et décaleraient des versets bien placés. Dans ce cas on garde l'interpolation.
            times = [c[2] for c in chosen]
            if a in first_tok:
                times.insert(0, hyp_t[ja][0])
            if b in first_tok:
                times.append(hyp_t[jb][0])
            if any(t2 - t1 < MIN_VERSE for t1, t2 in zip(times, times[1:])):
                continue
            for i, c in zip(firsts, chosen):
                verse_place[first_tok[i]] = c

    verse_start, verse_quality = {}, {}
    placed = 0
    for vi in range(len(verse_keys)):
        idx = verse_toks[vi][0]
        s = starts[idx]
        if s is None:
            continue
        place = verse_place.get(vi)
        if place is not None:
            s, k = place[2], place[3]
            if k is not None:
                # début sur un mot transcrit : avancé dans le silence qui le précède
                pe = hyp_t[k - 1][1] if k > 0 else 0.0
                if pe < s:
                    s = max(pe, s - LEAD)
            placed += 1
        else:
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
            # Le mot « chapitre » lui-même, quand il est transcrit juste avant : plus sûr que la fin
            # estimée du chapitre précédent (qui peut être fausse si sa dernière phrase diffère du texte).
            said = [j for j, (s, _) in enumerate(hyp_t) if hyp[j] == "chapitre" and vs - 8 < s < vs - 0.05]
            if said:
                j = said[-1]
                floor = max(hyp_t[j - 1][1], hyp_t[j][0] - LEAD) if j > 0 else 0.0
                cands = [hyp_t[j][0]]
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
    print(f"jetons alignés : {matched}/{len(ref)} ({matched / len(ref):.1%}), presque pareils : {fuzzy}", file=sys.stderr)
    print(f"débuts de verset placés sur un silence : {placed}/{len(verse_keys)}", file=sys.stderr)
    print(f"versets faibles (<50% de mots reconnus) : {len(weak)} {weak[:30]}", file=sys.stderr)
    times = sorted(verse_start[vi] for vi in verse_start if verse_keys[vi][0] not in missing)
    nonmono = [t for a, t in zip(times, times[1:]) if t - a < 0.05]
    print(f"versets de durée quasi nulle : {len(nonmono)}", file=sys.stderr)
    report = {"code": code, "aligned": round(matched / len(ref), 4), "weak": len(weak),
              "verses": len(verse_keys), "nonmonotonic": len(nonmono), "missing_chapters": missing,
              "fuzzy": fuzzy, "placed": placed}
    print("REPORT " + json.dumps(report), file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:5])
