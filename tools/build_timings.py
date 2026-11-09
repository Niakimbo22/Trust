"""Calcule content/timings/<CODE>.json pour un livre (utilisé par la CI « Contenu »).

Étapes : télécharge chaque fichier audio du livre (bible_manifest.json), le décode en
WAV 16 kHz mono (fichier temporaire, jamais publié), transcrit avec horodatage
(faster-whisper « small »), puis aligne avec content/text/<CODE>.json.
L'audio (CC BY-NC-ND) n'est ni modifié ni redistribué : il sert uniquement à mesurer.

Usage : python3 tools/build_timings.py CODE [dossier_travail]
"""
import json
import os
import subprocess
import sys
import time
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 16000
CHUNK = 600  # secondes


def run(cmd):
    subprocess.run(cmd, check=True)


def download(url, dest):
    for attempt in range(6):
        try:
            run(["curl", "-sSL", "--fail", "--retry", "3", "--max-time", "1800", "-o", dest, url])
            if os.path.getsize(dest) > 10000:
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(30 * (attempt + 1))
    raise RuntimeError(f"téléchargement impossible : {url}")


def transcribe(model, wav_path):
    w = wave.open(wav_path)
    n = w.getnframes()
    words = []
    for off in range(0, n, CHUNK * SR):
        w.setpos(off)
        a = np.frombuffer(w.readframes(min(CHUNK * SR, n - off)), np.int16).astype(np.float32) / 32768
        segs, _ = model.transcribe(a, language="fr", word_timestamps=True, beam_size=1,
                                   condition_on_previous_text=False)
        base = off / SR
        for s in segs:
            for wd in s.words:
                words.append([round(base + wd.start, 2), round(base + wd.end, 2), wd.word])
        print(f"  {base / 60:.0f} min transcrites", flush=True)
    return words


def main(code, work="work"):
    from faster_whisper import WhisperModel

    os.makedirs(work, exist_ok=True)
    manifest = os.path.join(ROOT, "tools", "bible_manifest.json")
    book = next(b for b in json.load(open(manifest, encoding="utf-8"))["books"] if b["code"] == code)
    model = WhisperModel("small", device="cpu", compute_type="int8", cpu_threads=os.cpu_count() or 2)

    files = []
    for i, a in enumerate(book["audio"]):
        print(f"{code} fichier {i + 1}/{len(book['audio'])} : {a['url']}", flush=True)
        ogg, wav = os.path.join(work, f"{code}_{i}.ogg"), os.path.join(work, f"{code}_{i}.wav")
        download(a["url"], ogg)
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", ogg, "-ac", "1", "-ar", str(SR), wav])
        os.remove(ogg)
        files.append(transcribe(model, wav))
        os.remove(wav)

    words_path = os.path.join(work, f"{code}_words.json")
    json.dump({"files": files}, open(words_path, "w"), ensure_ascii=False)
    out = os.path.join(ROOT, "content", "timings", f"{code}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        subprocess.run([sys.executable, os.path.join(ROOT, "tools", "align_timings.py"), code,
                        os.path.join(ROOT, "content", "text", f"{code}.json"), words_path, manifest],
                       check=True, stdout=f)


if __name__ == "__main__":
    main(*sys.argv[1:3])
