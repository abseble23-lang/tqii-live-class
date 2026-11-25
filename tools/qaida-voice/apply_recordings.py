"""Pull teacher recordings (Supabase bucket qaida-voice) into web-qaida/audio.
Runs in GitHub Actions; authenticates with the job's GitHub OIDC token.
Recorded clip replaces the AI clip of the same sentence; deleted recordings fall back to the AI clip.
Prints CHANGED=<n>."""
import json, os, shutil, subprocess, sys, tempfile, urllib.request

FN = "https://zbyzvzutudyxhecuytzo.supabase.co/functions/v1/qaida-voice-export"
BASE = "https://zbyzvzutudyxhecuytzo.supabase.co/storage/v1"
AUD, AI, SENT = "web-qaida/audio", "tools/qaida-voice/ai-audio", "tools/qaida-voice/sentences.json"

def oidc():
    u = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"] + "&audience=tqii-qaida-voice"
    r = urllib.request.Request(u, headers={"Authorization": "Bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
    return json.load(urllib.request.urlopen(r))["value"]

def process(src, dst, sr=48000):
    """Clean up a phone recording: denoise, trim the silence at both ends, even loudness, Opus."""
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", src, "-ac", "1", "-ar", str(sr),
                          "-af", "highpass=f=80,lowpass=f=12000,afftdn=nf=-30", "-f", "f32le", "-"], capture_output=True).stdout
    a = np.frombuffer(raw, np.float32)
    if len(a) < sr // 4: return False
    hop = sr // 100; n = len(a) // hop
    rms = np.sqrt((a[: n * hop].reshape(n, hop) ** 2).mean(1) + 1e-12)
    db = 20 * np.log10(rms); loud = np.where(db > db.max() - 38)[0]
    if not len(loud): return False
    s0 = max(0, (loud[0] - 8) * hop); s1 = min(len(a), (loud[-1] + 12) * hop)
    a = a[s0:s1]
    p = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(sr), "-ac", "1", "-i", "-",
                        "-af", "loudnorm=I=-18:TP=-1.5:LRA=9,adelay=40,apad=pad_dur=0.25", "-ar", str(sr),
                        "-c:a", "libopus", "-b:a", "32k", "-application", "voip", dst], input=a.tobytes())
    return p.returncode == 0

items = json.load(urllib.request.urlopen(urllib.request.Request(FN, headers={"Authorization": "Bearer " + oidc()})))
sent = json.load(open(SENT, encoding="utf-8"))
man = json.load(open(f"{AUD}/manifest.json", encoding="utf-8"))
hum_p = f"{AUD}/human.json"; hum = json.load(open(hum_p))
seen, changed = set(), 0
tmp = tempfile.mkdtemp()
for it in items:
    lang, base = it["name"].split("/"); idx = int(base[:4]); fn = f"{lang}{idx:04d}.ogg"
    if idx >= len(sent[lang]): continue
    seen.add(fn)
    if hum.get(fn) == it["updated"]: continue
    url = it["url"] if it["url"].startswith("http") else BASE + it["url"]
    src = os.path.join(tmp, base); urllib.request.urlretrieve(url, src)
    r = process(src, f"{AUD}/{fn}")
    if not r: print("skip (decode failed or silent)", it["name"]); continue
    man[lang][sent[lang][idx][0]] = fn; hum[fn] = it["updated"]; changed += 1
for fn in [f for f in hum if f not in seen]:           # recording deleted -> back to AI voice
    lang, idx = fn[:2], int(fn[2:6]); key = sent[lang][idx][0]
    if os.path.exists(f"{AI}/{fn}"): shutil.copy(f"{AI}/{fn}", f"{AUD}/{fn}")
    else:
        man[lang].pop(key, None)
        if os.path.exists(f"{AUD}/{fn}"): os.remove(f"{AUD}/{fn}")
    del hum[fn]; changed += 1
json.dump(man, open(f"{AUD}/manifest.json", "w", encoding="utf-8"), ensure_ascii=False)
json.dump(hum, open(hum_p, "w"), indent=0, sort_keys=True)
print(f"recordings={len(seen)} CHANGED={changed}")
with open(os.environ.get("GITHUB_OUTPUT", "/dev/null"), "a") as f: f.write(f"changed={changed}\n")
