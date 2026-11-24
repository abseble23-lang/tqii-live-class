/* TQII Qaida — makes the web Speech API work inside the Android app by sending speech to
   Android's own TextToSpeech engine (Google voices: Bangla + English, works offline once installed). */
(function () {
  if (!window.AndroidTTS) return;
  var cbs = {}, seq = 0, voices = [];

  function SpeechSynthesisUtterance(text) {
    this.text = text || ''; this.lang = ''; this.voice = null;
    this.rate = 1; this.pitch = 1; this.volume = 1;
    this.onstart = null; this.onend = null; this.onerror = null;
  }

  var synth = {
    speaking: false, pending: false, paused: false, onvoiceschanged: null,
    getVoices: function () { return voices; },
    speak: function (u) {
      var id = 'u' + (++seq);
      cbs[id] = u; synth.speaking = true;
      AndroidTTS.speak(id, String(u.text || ''), u.voice ? u.voice.lang : (u.lang || ''),
        u.voice ? u.voice.name : '', Number(u.rate) || 1, Number(u.pitch) || 1);
    },
    cancel: function () { cbs = {}; synth.speaking = false; AndroidTTS.stop(); },
    pause: function () {}, resume: function () {},
    addEventListener: function (t, f) { if (t === 'voiceschanged') synth.onvoiceschanged = f; },
    removeEventListener: function () {}
  };

  window.__ttsEvent = function (id, type) {
    var u = cbs[id]; if (!u) return;
    if (type === 'start') { if (u.onstart) u.onstart({ type: 'start', utterance: u }); return; }
    delete cbs[id];
    if (!Object.keys(cbs).length) synth.speaking = false;
    var f = type === 'end' ? u.onend : u.onerror;
    if (f) f({ type: type, utterance: u });
  };

  window.__ttsVoices = function (json) {
    try {
      voices = JSON.parse(json).map(function (v) {
        return { name: v.name, lang: v.lang, voiceURI: v.name, localService: true, default: false };
      });
    } catch (e) { voices = []; }
    if (typeof synth.onvoiceschanged === 'function') synth.onvoiceschanged({ type: 'voiceschanged' });
  };

  try { Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true }); }
  catch (e) { window.speechSynthesis = synth; }
  window.SpeechSynthesisUtterance = SpeechSynthesisUtterance;
  try { window.__ttsVoices(AndroidTTS.voices()); } catch (e) {}
})();
