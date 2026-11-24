"""Generate embedded AI voice clips for the TQII Qaida app.
Usage: python synth.py sentences.json outdir [limit]
Each sentence the app can speak -> one .ogg (Opus) file + manifest.json {lang:{key:file}}.
"""
import json, os, re, sys, subprocess, unicodedata, warnings, logging
import numpy as np, torch
torch.backends.mkldnn.enabled = False  # oneDNN caches per input shape -> memory grows without bound
warnings.filterwarnings("ignore"); logging.disable(logging.WARNING)
from num2words import num2words
from TTS.utils.synthesizer import Synthesizer
import contextlib, io

SRC, OUT = sys.argv[1], sys.argv[2]
LIMIT = int(sys.argv[3]) if len(sys.argv) > 3 else None
SPK = os.environ.get("SPK", "male")
os.makedirs(OUT, exist_ok=True)
M = "model"

def load(l):
    return Synthesizer(tts_checkpoint=f"{M}/{l}/fastpitch/best_model.pth", tts_config_path=f"{M}/{l}/fastpitch/config2.json",
                       vocoder_checkpoint=f"{M}/{l}/hifigan/best_model.pth", vocoder_config=f"{M}/{l}/hifigan/config.json", use_cuda=False)
syn = {"bn": load("bn"), "en": load("en")}
SR = 22050

BD = "০১২৩৪৫৬৭৮৯"
def bdig(s): return s.translate(str.maketrans(BD, "0123456789"))

def num_words(m, lang):
    n = int(m.group(0))
    try: return " " + num2words(n, lang=lang) + " "
    except Exception: return m.group(0)

def prep_bn(t):
    t = bdig(t)
    t = re.sub(r"(\d)\s*:\s*(\d)", r"\1, \2", t)
    t = re.sub(r"\d+", lambda m: num_words(m, "bn"), t)
    t = t.replace("ঽ", "").replace("৳", " টাকা ")
    t = re.sub(r"[()\[\]{};:/=≈+×*“”\"]", ", ", t).replace("…", ".")
    t = re.sub(r"\s*,[\s,]*", ", ", t)
    return re.sub(r"\s+", " ", t).strip(" ,")

def prep_en(t):
    t = unicodedata.normalize("NFD", bdig(t)).replace("TQII", "T. Q. I. I.")
    t = re.sub(r"[̀-ͯ]", "", t).replace("ʿ", "").replace("ʾ", "").replace("’", "'").replace("‘", "'")
    t = t.replace("≈", " about ").replace("=", " equals ").replace("+", " plus ").replace("×", " times ").replace("&", " and ")
    t = re.sub(r"(\d)\s*:\s*(\d)", r"\1, \2", t)
    t = re.sub(r"\d+", lambda m: num_words(m, "en"), t)
    t = re.sub(r"[()\[\]{};:/*“”\"]", ", ", t).replace("…", ".")
    t = re.sub(r"\s*,[\s,]*", ", ", t)
    return re.sub(r"\s+", " ", t).strip(" ,")

def script_runs(s):
    """Split into runs of Bangla vs Latin; neutral chars stick to the current run."""
    runs = []
    for ch in s:
        c = "B" if "ঀ" <= ch <= "৿" else ("L" if (ch.isalpha() and ord(ch) < 0x2000) else None)
        if c is None or (runs and runs[-1][0] == c):
            if runs: runs[-1][1] += ch
            else: runs.append([None, ch])
        else:
            if runs and runs[-1][0] is None: runs[-1] = [c, runs[-1][1] + ch]
            else: runs.append([c, ch])
    return [(c, t) for c, t in runs if c]

def tts(lang, text):
    text = prep_bn(text) if lang == "bn" else prep_en(text)
    if not re.search(r"\w", text): return None
    if text[-1] not in ".?!।": text += "।" if lang == "bn" else "."
    with contextlib.redirect_stdout(io.StringIO()):
        w = syn[lang].tts(text, speaker_name=SPK, split_sentences=False)
    return np.asarray(w, dtype=np.float32)

def render(sentence, base_lang):
    pieces = []
    for c, t in script_runs(sentence):
        lang = "bn" if c == "B" else "en"
        a = tts(lang, t)
        if a is not None and len(a):
            if pieces: pieces.append(np.zeros(int(SR * 0.12), np.float32))
            pieces.append(a)
    if not pieces: return None
    a = np.concatenate(pieces)
    peak = np.abs(a).max() or 1
    a = a / peak * 0.89
    return np.concatenate([np.zeros(int(SR * 0.03), np.float32), a, np.zeros(int(SR * 0.22), np.float32)])

def encode(a, path):
    pcm = (np.clip(a, -1, 1) * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-af", "highpass=f=70", "-c:a", "libopus", "-b:a", "24k", "-application", "voip", path], input=pcm, check=True)

data = json.load(open(SRC))
manp = os.path.join(OUT, "manifest.json")
man = json.load(open(manp)) if os.path.exists(manp) else {"bn": {}, "en": {}}
done = 0
for lang in ("bn", "en"):
    for i, (key, sent) in enumerate(data[lang]):
        if LIMIT and done >= LIMIT: break
        fn = f"{lang}{i:04d}.ogg"
        if key in man[lang] and os.path.exists(os.path.join(OUT, man[lang][key])): continue
        try:
            a = render(sent, lang)
        except Exception as e:
            print("FAIL", lang, i, repr(sent)[:80], e, flush=True); continue
        if a is None: continue
        encode(a, os.path.join(OUT, fn))
        man[lang][key] = fn; done += 1
        if done % 20 == 0:
            json.dump(man, open(manp, "w"), ensure_ascii=False)
            print(lang, i, len(data[lang]), flush=True)
json.dump(man, open(manp, "w"), ensure_ascii=False)
print("DONE", done, {k: len(v) for k, v in man.items()})
