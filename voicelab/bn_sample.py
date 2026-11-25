import json, sys, time, os, soundfile as sf
sys.path.insert(0, "kit")
os.chdir("kit")
from infer import BanglaTTS
t0 = time.time(); tts = BanglaTTS("."); print("load", round(time.time()-t0), "s", flush=True)
S = json.load(open("../voicelab/samples.json"))["bn"]
os.makedirs("../voicelab/out", exist_ok=True)
for ref in ["ref_male", "ref_female"]:
    for i, s in enumerate(S):
        t0 = time.time(); w, sr = tts.tts(s, f"voices/{ref}.wav")
        sf.write(f"../voicelab/out/bn_{ref}_{i}.wav", w, sr)
        print(ref, i, f"{len(w)/sr:.1f}s audio in {time.time()-t0:.1f}s", flush=True)
