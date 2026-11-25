"""Merge shard outputs into web-qaida/audio; teacher recordings (human.json) are never overwritten."""
import glob, json, os, shutil, sys
SHARDS, AUD, AI = sys.argv[1], "web-qaida/audio", "tools/qaida-voice/ai-audio"
hum = json.load(open(f"{AUD}/human.json"))
man = json.load(open(f"{AUD}/manifest.json", encoding="utf-8"))
ai_man = json.load(open(f"{AI}/manifest.json", encoding="utf-8")) if os.environ.get("PARTIAL") else {"bn": {}, "en": {}}; n = 0
for p in glob.glob(f"{SHARDS}/**/part*.json", recursive=True):
    d = os.path.dirname(p); part = json.load(open(p, encoding="utf-8"))
    for l in ("bn", "en"):
        for k, fn in part[l].items():
            shutil.copy(f"{d}/{fn}", f"{AI}/{fn}"); ai_man[l][k] = fn
            if fn not in hum: shutil.copy(f"{d}/{fn}", f"{AUD}/{fn}"); man[l][k] = fn; n += 1
total = sum(len(v) for v in ai_man.values())
if total < 1300 and not os.environ.get("PARTIAL"): sys.exit(f"only {total} clips generated - not replacing")
man["voice"] = {"bn": "BanglaBox (MIT)", "en": "Kokoro-82M af_heart (Apache-2.0)"}
json.dump(man, open(f"{AUD}/manifest.json", "w", encoding="utf-8"), ensure_ascii=False)
json.dump(ai_man, open(f"{AI}/manifest.json", "w", encoding="utf-8"), ensure_ascii=False)
print("replaced", n, "of", total)
