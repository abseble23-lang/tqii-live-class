"""Regenerate the Qaida voice clips with Azure Speech neural voices (Bangla-Bangladesh + English).
Env: AZURE_SPEECH_KEY, AZURE_SPEECH_REGION, BN_VOICE, EN_VOICE.  Usage: python azure_synth.py sentences.json out_dir
"""
import json, os, re, sys, time, urllib.request, urllib.error
from xml.sax.saxutils import escape

KEY, REGION = os.environ["AZURE_SPEECH_KEY"], os.environ["AZURE_SPEECH_REGION"]
BN = os.environ.get("BN_VOICE") or "bn-BD-PradeepNeural"
EN = os.environ.get("EN_VOICE") or "en-US-AndrewNeural"
RATE = os.environ.get("RATE") or "-6%"
SRC, OUT = sys.argv[1], sys.argv[2]
URL = f"https://{REGION}.tts.speech.microsoft.com/cognitiveservices/v1"

def runs(s):
    out = []
    for ch in s:
        c = "B" if "ঀ" <= ch <= "৿" else ("L" if (ch.isalpha() and ord(ch) < 0x2000) else None)
        if c is None or (out and out[-1][0] == c):
            if out: out[-1][1] += ch
            else: out.append([None, ch])
        elif out and out[-1][0] is None: out[-1] = [c, out[-1][1] + ch]
        else: out.append([c, ch])
    return [(c, t.strip()) for c, t in out if c and re.search(r"\w", t)]

def tidy(t):
    t = t.replace("TQII", "T Q I I")
    t = re.sub(r"[()\[\]{}]", ", ", t)
    return re.sub(r"\s*,[\s,]*", ", ", t).strip(" ,")

def ssml(sentence, lang):
    parts = runs(sentence) or [("B" if lang == "bn" else "L", sentence)]
    body = ""
    for c, t in parts:
        v, loc = (BN, "bn-BD") if c == "B" else (EN, "en-US")
        body += f'<voice name="{v}"><prosody rate="{RATE}">{escape(tidy(t))}</prosody></voice>'
    return f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="{"bn-BD" if lang=="bn" else "en-US"}">{body}</speak>'

def synth(x):
    for attempt in range(6):
        req = urllib.request.Request(URL, data=x.encode("utf-8"), method="POST", headers={
            "Ocp-Apim-Subscription-Key": KEY, "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": "ogg-24khz-16bit-mono-opus", "User-Agent": "tqii-qaida"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r: return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503): time.sleep(5 * (attempt + 1)); continue
            raise SystemExit(f"Azure error {e.code}: {e.read()[:300]!r}")
        except Exception: time.sleep(5)
    raise SystemExit("Azure kept failing")

data = json.load(open(SRC, encoding="utf-8"))
os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith(".ogg"): os.remove(os.path.join(OUT, f))
man, n = {"bn": {}, "en": {}}, 0
for lang in ("bn", "en"):
    for i, (key, sent) in enumerate(data[lang]):
        if not runs(sent): continue
        fn = f"{lang}{i:04d}.ogg"
        open(os.path.join(OUT, fn), "wb").write(synth(ssml(sent, lang)))
        man[lang][key] = fn; n += 1
        if n % 100 == 0: print(n, flush=True)
        time.sleep(0.35)  # stay under the free tier's request rate
man["voice"] = {"bn": BN, "en": EN}
json.dump(man, open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("DONE", {k: len(v) for k, v in man.items() if k != "voice"})
