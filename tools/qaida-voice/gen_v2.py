"""Qaida voice v2: BanglaBox (Bangladeshi Bangla, MIT) + Kokoro-82M (English, Apache-2.0).
python gen_v2.py <sentences.json> <out_dir> <shard> <num_shards>
Writes <out_dir>/<lang><idx>.ogg and <out_dir>/part.json {lang:{key:file}}."""
import json, os, re, sys, subprocess, unicodedata, time
import numpy as np, torch

SRC, OUT, SH, NS = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
KIT = os.path.abspath(os.environ.get("KIT", "kit"))
BN_REF = os.path.join(KIT, "voices", os.environ.get("BN_REF", "ref_female.wav"))
EN_VOICE = os.environ.get("EN_VOICE", "af_heart")
SR = 24000
os.makedirs(OUT, exist_ok=True)
torch.set_num_threads(os.cpu_count() or 4)

sys.path.insert(0, KIT); cwd = os.getcwd(); os.chdir(KIT)
from infer import BanglaTTS
BB = BanglaTTS(KIT); os.chdir(cwd)
from kokoro import KPipeline
KP = KPipeline(lang_code="a")

def runs(s):
    out = []
    for ch in s:
        c = "B" if "ঀ" <= ch <= "৿" else ("L" if (ch.isalpha() and ord(ch) < 0x2000) else None)
        if c is None or (out and out[-1][0] == c):
            if out: out[-1][1] += ch
            else: out.append([None, ch])
        elif out and out[-1][0] is None: out[-1] = [c, out[-1][1] + ch]
        else: out.append([c, ch])
    return [(c, t) for c, t in out if c]

def tidy_common(t):
    t = re.sub(r"([?!।:;,])(?=\S)", r"\1 ", t)
    t = re.sub(r"\(\s*[,;]?\s*\)|\[\s*\]", " ", t)
    t = re.sub(r"[()\[\]{}/|*“”\"]", ", ", t)
    t = re.sub(r"\s*,[\s,]*", ", ", t)
    return re.sub(r"\s+", " ", t).strip(" ,;:!-—–")

BD = "০১২৩৪৫৬৭৮৯"
def prep_en(t):
    t = unicodedata.normalize("NFD", t.translate(str.maketrans(BD, "0123456789")))
    t = re.sub(r"[̀-ͯ]", "", t).replace("ʿ", "").replace("ʾ", "").replace("’", "'").replace("TQII", "T.Q.I.I.")
    t = t.replace("≈", " about ").replace("=", " equals ").replace("+", " plus ").replace("&", " and ")
    t = re.sub(r"(\d)\s*:\s*(\d)", r"\1, \2", t)
    return tidy_common(t)

def prep_bn(t):
    t = t.replace("৳", " টাকা ").replace("=", " সমান ").replace("+", " যোগ ")
    return tidy_common(t)

def ok_len(w, text):
    d = len(w) / SR; n = max(1, len(re.sub(r"\s", "", text)))
    return max(0.25, n * 0.055 - 0.3) < d < 1.2 + n * 0.22

def phrases(t, limit=70):
    """BanglaBox loses words on long inputs: split at punctuation into short phrases."""
    if len(t) <= limit: return [t]
    parts, cur = [], ""
    for p in re.split(r"(?<=[।?!;,:])\s+", t):
        if cur and len(cur) + len(p) > limit: parts.append(cur); cur = p
        else: cur = (cur + " " + p).strip()
    if cur: parts.append(cur)
    out = []
    for p in parts:
        while len(p) > limit * 1.4:
            cut = p.rfind(" ", 0, limit); cut = cut if cut > 20 else limit
            out.append(p[:cut]); p = p[cut:].strip()
        out.append(p)
    return out

def say_bn(t, seed):
    best = None
    for k in range(3):
        torch.manual_seed(seed * 7 + k)
        w, sr = BB.tts(t, BN_REF); w = np.asarray(w, np.float32)
        if sr != SR:
            import librosa; w = librosa.resample(w, orig_sr=sr, target_sr=SR)
        if ok_len(w, t): return w
        best = w if best is None or abs(len(w)) < abs(len(best)) else best
    return best

def say_en(t):
    return np.concatenate([np.asarray(a, np.float32) for _, _, a in KP(t, voice=EN_VOICE, speed=0.92)])

def render(sentence, seed):
    pieces = []
    for c, t in runs(sentence):
        t = prep_bn(t) if c == "B" else prep_en(t)
        if not re.search(r"\w", t): continue
        for ph in (phrases(t) if c == "B" else [t]):
            w = say_bn(ph, seed + len(pieces)) if c == "B" else say_en(ph)
            if w is None or not len(w): continue
            if pieces: pieces.append(np.zeros(int(SR * 0.15), np.float32))
            pieces.append(w)
    if not pieces: return None
    a = np.concatenate(pieces); a = a / (np.abs(a).max() or 1) * 0.9
    return np.concatenate([np.zeros(int(SR * 0.04), np.float32), a, np.zeros(int(SR * 0.25), np.float32)])

def encode(a, path):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-af", "highpass=f=60,loudnorm=I=-18:TP=-1.5:LRA=9", "-ar", "48000",
                    "-c:a", "libopus", "-b:a", "32k", "-application", "voip", path], input=a.tobytes(), check=True)

data = json.load(open(SRC, encoding="utf-8"))
jobs = sorted([(len(s), l, i, k, s) for l in ("bn", "en") for i, (k, s) in enumerate(data[l])], reverse=True)
ONLY = set(filter(None, os.environ.get("ONLY", "").split(",")))
if ONLY: jobs = [j for j in jobs if f"{j[1]}{j[2]:04d}" in ONLY]
SEED_OFF = int(os.environ.get("SEED_OFFSET", "0"))
mine = [j for n, j in enumerate(jobs) if n % NS == SH]
part = {"bn": {}, "en": {}}; t0 = time.time()
for n, (_, l, i, k, s) in enumerate(mine):
    try: a = render(s, i + SEED_OFF)
    except Exception as e: print("FAIL", l, i, e, flush=True); continue
    if a is None: continue
    fn = f"{l}{i:04d}.ogg"; encode(a, os.path.join(OUT, fn)); part[l][k] = fn
    if n % 10 == 0: print(f"{n}/{len(mine)} {time.time()-t0:.0f}s", flush=True)
json.dump(part, open(os.path.join(OUT, f"part{SH}.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("DONE", len(part["bn"]), len(part["en"]))
