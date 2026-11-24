# Qaida built-in AI voice

`web-qaida/audio/` holds one Opus clip per sentence the Qaida book can speak (Bangla + English),
and `manifest.json` maps each sentence (normalised with `vkey()` from index.html) to its clip.
The book plays these clips first and falls back to the phone's own voice only for a sentence without a clip.

Voices: AI4Bharat Indic-TTS FastPitch + HiFi-GAN (MIT licence), `bn` and `en` models, male speaker.

Regenerate after changing lesson text:
1. `node extract.js ../../web-qaida/index.html` → `sentences.json`
2. Download `bn.zip` and `en.zip` from https://github.com/AI4Bharat/Indic-TTS/releases/tag/v1-checkpoints-release into `model/`
   (set `speakers_file` in each fastpitch config to the local `speakers.pth`, saved as `config2.json`).
3. `pip install coqui-tts num2words torch` then `python synth.py sentences.json ../../web-qaida/audio`
   (resumable; only new sentences are generated).
4. `node cover.js ../../web-qaida/index.html ../../web-qaida/audio/manifest.json` should report no misses.
