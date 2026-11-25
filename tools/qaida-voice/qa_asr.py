"""ASR check: transcribe new clips (and the previous version) and score similarity to the text.
python qa_asr.py sentences.json manifest.json old_dir shard nshards out.json"""
import json, re, sys, difflib, os, unicodedata
from faster_whisper import WhisperModel
S, MAN, OLD, SH, NS, OUT = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), sys.argv[6]
d = json.load(open(S, encoding="utf-8")); man = json.load(open(MAN, encoding="utf-8"))
m = WhisperModel("large-v3-turbo", device="cpu", compute_type="int8", cpu_threads=4)
BD = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")
def norm(t):
    t = unicodedata.normalize("NFD", t.lower().translate(BD)); t = re.sub(r"[̀-ͯ]", "", t)
    return re.sub(r"[^\w]|_|\d", "", t)
def score(f, text, lang):
    if not os.path.exists(f): return None
    segs, _ = m.transcribe(f, language=lang, beam_size=3, vad_filter=False)
    hyp = " ".join(s.text for s in segs)
    return round(difflib.SequenceMatcher(None, norm(text), norm(hyp)).ratio(), 3), hyp
res = []
items = [(l, i, k, s) for l in ("bn", "en") for i, (k, s) in enumerate(d[l]) if man[l].get(k)]
for n, (l, i, k, s) in enumerate(items):
    if n % NS != SH or len(norm(s)) < 4: continue
    fn = man[l][k]; new = score(f"web-qaida/audio/{fn}", s, l); old = score(f"{OLD}/{fn}", s, l)
    res.append({"f": fn, "text": s, "new": new[0], "old": old[0] if old else None, "hyp": new[1]})
json.dump(res, open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
print("done", len(res))
