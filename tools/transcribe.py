"""Transcription horodatée (faster-whisper small, CPU) d'un audio WAV 16 kHz mono, par tranches de 10 min.
Usage : python3 transcribe.py audio16k.wav words.json   (ffmpeg -i livre.ogg -ac 1 -ar 16000 audio16k.wav)
Usage interne uniquement : l'audio n'est ni modifié ni redistribué."""
import json,sys,time,wave,os
import numpy as np
from faster_whisper import WhisperModel
src,out=sys.argv[1],sys.argv[2]
SR=16000; CH=600
w=wave.open(src); n=w.getnframes()
m=WhisperModel("small",device="cpu",compute_type="int8",cpu_threads=2)
t=time.time(); words=[]
for off in range(0,n,CH*SR):
    w.setpos(off)
    a=np.frombuffer(w.readframes(min(CH*SR,n-off)),np.int16).astype(np.float32)/32768
    segs,_=m.transcribe(a,language="fr",word_timestamps=True,beam_size=1,condition_on_previous_text=False)
    base=off/SR
    for s in segs:
        for wd in s.words: words.append([round(base+wd.start,2),round(base+wd.end,2),wd.word])
    print(f"chunk {base:.0f}s ok, {time.time()-t:.0f}s elapsed, {len(words)} words",flush=True)
    json.dump(words,open(out,"w"),ensure_ascii=False)
print("done",len(words),time.time()-t,flush=True)
