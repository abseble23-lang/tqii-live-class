import json, os, time, soundfile as sf, numpy as np
from kokoro import KPipeline
S = json.load(open("voicelab/samples.json"))["en"]
os.makedirs("voicelab/out", exist_ok=True)
for lc, voices in [("a", ["am_michael", "am_adam", "af_heart"]), ("b", ["bm_george", "bf_emma"])]:
    p = KPipeline(lang_code=lc)
    for v in voices:
        for i, s in enumerate(S):
            t0 = time.time(); w = np.concatenate([a for _, _, a in p(s, voice=v, speed=0.95)])
            sf.write(f"voicelab/out/en_{v}_{i}.wav", w, 24000); print(v, i, f"{len(w)/24000:.1f}s in {time.time()-t0:.1f}s", flush=True)
