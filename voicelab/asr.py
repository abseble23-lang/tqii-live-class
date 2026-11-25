import json, glob, re, os
from faster_whisper import WhisperModel
m = WhisperModel("large-v3-turbo", device="cpu", compute_type="int8")
S = json.load(open("voicelab/samples.json"))
def cer(a, b):
    a = re.sub(r"[^\w]", "", a.lower()); b = re.sub(r"[^\w]", "", b.lower())
    import difflib; sm = difflib.SequenceMatcher(None, a, b); return round(1 - sm.ratio(), 2)
for f in sorted(glob.glob("voicelab/out/*.wav") + glob.glob("voicelab/old/*.ogg")):
    n = os.path.basename(f); lang = n[:2]; i = int(re.findall(r"_(\d)\.", n)[0])
    segs, _ = m.transcribe(f, language=lang, beam_size=5)
    txt = " ".join(s.text for s in segs).strip()
    print(f"{n:28s} diff={cer(S[lang][i], txt):.2f} | {txt}", flush=True)
