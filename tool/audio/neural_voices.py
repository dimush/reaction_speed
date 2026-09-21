#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
neural_voices.py -- expressive "voice actor" raw takes for Reaction Speed.

Local neural TTS: Chatterbox Multilingual (Resemble AI, MIT licence, model
ResembleAI/chatterbox on Hugging Face).  Runs CPU-only.

This script ONLY produces raw takes at tool/audio/recordings/<line>_<lang>.wav
(mono, 24 kHz, 16-bit PCM, trimmed, peak-normalised to -3 dBFS).  The character
DSP chain (GRUFF / SQUEAKY / SHOUTY), SFX, music and res/raw* belong to the
other audio agent -- nothing here writes there.

Usage (from the repo root, with the dedicated venv):

    tool\\audio\\.venv-tts\\Scripts\\python.exe tool\\audio\\neural_voices.py refs
    tool\\audio\\.venv-tts\\Scripts\\python.exe tool\\audio\\neural_voices.py generate
    tool\\audio\\.venv-tts\\Scripts\\python.exe tool\\audio\\neural_voices.py select

`generate` is resumable: a take whose WAV already exists is skipped.
See README_neural_voices.md for install commands and runtime figures.
"""

import os
import sys
import json
import time
import math
import argparse

HERE = os.path.dirname(os.path.abspath(__file__))
REC = os.path.join(HERE, "recordings")
ALT = os.path.join(REC, "_alternates")
RAW = os.path.join(ALT, "raw")          # every generated take, untrimmed
REFS = os.path.join(ALT, "refs")        # voice-prompt reference clips
SR = 24000                              # Chatterbox native sample rate

for d in (REC, ALT, RAW, REFS):
    os.makedirs(d, exist_ok=True)


# --------------------------------------------------------------------------
# Lines.  Per line/language: a list of text variants that coax the wanted
# emotion.  (text, expected_syllables, is_canonical)
# --------------------------------------------------------------------------

# character is informational only -- the DSP chain is applied downstream.
CHARACTER = {
    "voice_ready":      ("SHOUTY",  "rising, teasing"),
    "voice_too_early":  ("GRUFF",   "grumpy scolding"),
    "voice_tier_1":     ("GRUFF",   "mocking"),
    "voice_tier_2":     ("SQUEAKY", "pleasantly surprised"),
    "voice_tier_3":     ("SHOUTY",  "impressed"),
    "voice_tier_4":     ("SQUEAKY", "ecstatic"),
    "voice_tier_5":     ("SHOUTY",  "maximum hype, drawn-out"),
    "voice_new_record": ("SHOUTY",  "celebratory"),
}

# reference clip used as audio_prompt_path, per character
REF_FOR = {"GRUFF": "gruff", "SQUEAKY": "squeaky", "SHOUTY": "neutral"}

VARIANTS = {
    "voice_ready": {
        "en": [("Ready?", 2, True), ("Ready?!", 2, True), ("Reeeady?!", 2, True)],
        "de": [(u"Bereit?", 2, True), (u"Bereit?!", 2, True), (u"Bereeeit?!", 2, True)],
        "ru": [(u"Готов?", 2, True), (u"Готов?!", 2, True), (u"Гото-о-ов?!", 2, True)],
    },
    "voice_too_early": {
        "en": [("Too early!", 3, True), ("Too early!!", 3, True), ("Ugh, too early!", 4, False)],
        "de": [(u"Zu früh!", 2, True), (u"Zu früh!!", 2, True), (u"Ach, zu früh!", 3, False)],
        "ru": [(u"Рано!", 2, True), (u"Рано!!", 2, True), (u"Эх, рано!", 3, False)],
    },
    "voice_tier_1": {
        "en": [("Keep practicing!", 4, True), ("Keep practicing!!", 4, True), ("Heh, keep practicing!", 5, False)],
        "de": [(u"Weiter üben!", 4, True), (u"Weiter üben!!", 4, True), (u"Tja, weiter üben!", 5, False)],
        "ru": [(u"Тренируйся!", 4, True), (u"Тренируйся!!", 4, True), (u"Эх, тренируйся!", 5, False)],
    },
    "voice_tier_2": {
        "en": [("Not bad!", 2, True), ("Not bad!!", 2, True), ("Oh, not bad!", 3, False)],
        "de": [(u"Nicht schlecht!", 2, True), (u"Nicht schlecht!!", 2, True), (u"Oh, nicht schlecht!", 3, False)],
        "ru": [(u"Неплохо!", 3, True), (u"Неплохо!!", 3, True), (u"О, неплохо!", 4, False)],
    },
    "voice_tier_3": {
        "en": [("Fast!", 1, True), ("Fast!!", 1, True), ("Whoa, fast!", 2, False)],
        "de": [(u"Schnell!", 1, True), (u"Schnell!!", 1, True), (u"Wow, schnell!", 2, False)],
        "ru": [(u"Быстро!", 2, True), (u"Быстро!!", 2, True), (u"Ух, быстро!", 3, False)],
    },
    "voice_tier_4": {
        "en": [("Lightning!", 2, True), ("Lightning!!!", 2, True), ("Wow, lightning!", 3, False)],
        "de": [(u"Blitzschnell!", 2, True), (u"Blitzschnell!!!", 2, True), (u"Wow, blitzschnell!", 3, False)],
        "ru": [(u"Молния!", 3, True), (u"Молния!!!", 3, True), (u"Ух, молния!", 4, False)],
    },
    "voice_tier_5": {
        "en": [("Superhuman!", 4, True), ("SUPERHUMAN!!!", 4, True), ("Suuuperhuman!!!", 4, True)],
        "de": [(u"Übermenschlich!", 4, True), (u"ÜBERMENSCHLICH!!!", 4, True), (u"Üübermenschlich!!!", 4, True)],
        "ru": [(u"Невероятно!", 5, True), (u"НЕВЕРОЯТНО!!!", 5, True), (u"Невероя-я-ятно!!!", 5, True)],
    },
    "voice_new_record": {
        "en": [("New record!", 3, True), ("NEW RECORD!!!", 3, True), ("A new record!", 4, False)],
        "de": [(u"Neuer Rekord!", 4, True), (u"NEUER REKORD!!!", 4, True), (u"Ein neuer Rekord!", 5, False)],
        "ru": [(u"Новый рекорд!", 5, True), (u"НОВЫЙ РЕКОРД!!!", 5, True), (u"Ого, новый рекорд!", 7, False)],
    },
}

LINES = list(VARIANTS.keys())
LANGS = ["en", "de", "ru"]

# canonical resource text, for the TAKES.md table
CANON = {ln: {lg: VARIANTS[ln][lg][0][0] for lg in LANGS} for ln in LINES}

# Take recipe: (variant_index, exaggeration, cfg_weight, temperature, use_char_ref, seed)
# Low cfg_weight goes with high exaggeration (Resemble's own guidance) --
# otherwise the model rambles.
TAKES = [
    (1, 0.80, 0.50, 0.70, True,  1001),
    (1, 1.10, 0.35, 0.80, True,  1002),
    (2, 1.40, 0.25, 0.90, True,  1003),
    (0, 1.00, 0.45, 0.65, False, 1004),
    (2, 1.60, 0.20, 1.00, False, 1005),
]


# --------------------------------------------------------------------------
# small audio helpers (numpy only)
# --------------------------------------------------------------------------

def _np():
    import numpy as np
    return np


def load_wav(path):
    import soundfile as sf
    np = _np()
    x, sr = sf.read(path, dtype="float32")
    x = np.asarray(x, dtype=np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    return x, sr


def save_wav_f32(path, x, sr=SR):
    import soundfile as sf
    np = _np()
    sf.write(path, np.asarray(x, dtype=np.float32), sr, subtype="FLOAT")


def save_wav_pcm16(path, x, sr=SR):
    import soundfile as sf
    np = _np()
    x = np.clip(np.asarray(x, dtype=np.float64), -1.0, 1.0)
    sf.write(path, x.astype(np.float32), sr, subtype="PCM_16")


def peak_normalise(x, dbfs=-3.0):
    np = _np()
    p = float(np.max(np.abs(x))) if x.size else 0.0
    if p <= 1e-9:
        return x
    return x * (10.0 ** (dbfs / 20.0) / p)


def resample_naive(x, factor):
    """Linear-interpolation resample by `factor` (pitch+formant shift for a
    reference clip; duration changes too, which is fine for a voice prompt)."""
    np = _np()
    n = int(round(len(x) / factor))
    if n < 2:
        return x
    idx = np.linspace(0.0, len(x) - 1.0, n)
    return np.interp(idx, np.arange(len(x), dtype=np.float64), x)


def env_db(x, sr=SR, hop=0.01, win=0.025):
    """Frame RMS in dBFS."""
    np = _np()
    h = max(1, int(hop * sr))
    w = max(h, int(win * sr))
    n = max(1, (len(x) - w) // h + 1)
    out = np.empty(n)
    for i in range(n):
        f = x[i * h: i * h + w]
        out[i] = math.sqrt(float(np.mean(f * f)) + 1e-20)
    return 20.0 * np.log10(out + 1e-12), h


def speech_segments(x, sr=SR, rel_db=32.0, min_seg=0.04):
    """Active-speech segments as (start_s, end_s), threshold relative to peak frame."""
    np = _np()
    db, h = env_db(x, sr)
    if db.size == 0:
        return []
    thr = db.max() - rel_db
    act = db > thr
    segs, s = [], None
    for i, a in enumerate(act):
        if a and s is None:
            s = i
        elif not a and s is not None:
            segs.append((s * h / sr, i * h / sr))
            s = None
    if s is not None:
        segs.append((s * h / sr, len(act) * h / sr))
    return [g for g in segs if (g[1] - g[0]) >= min_seg]


def trim_candidates(x, sr=SR):
    """Trim proposals: leading/trailing silence removal, then progressively
    tighter 'cut at the first long internal gap' variants.  This is silence
    trimming only -- never word extraction."""
    segs = speech_segments(x, sr)
    if not segs:
        return {"full": (0.0, len(x) / sr)}
    pad = 0.05
    out = {"full": (max(0.0, segs[0][0] - pad), min(len(x) / sr, segs[-1][1] + pad))}
    for gap in (1.0, 0.6, 0.35):
        end = segs[-1][1]
        for a, b in zip(segs, segs[1:]):
            if b[0] - a[1] >= gap:
                end = a[1]
                break
        out["gap%.2f" % gap] = (max(0.0, segs[0][0] - pad), min(len(x) / sr, end + pad))
    # de-duplicate
    seen, uniq = set(), {}
    for k, v in out.items():
        key = (round(v[0], 3), round(v[1], 3))
        if key in seen:
            continue
        seen.add(key)
        uniq[k] = v
    return uniq


def slice_s(x, a, b, sr=SR):
    return x[int(round(a * sr)): int(round(b * sr))]


def syllable_nuclei(x, sr=SR):
    """Count energy-envelope peaks above a floor, separated by >=90 ms."""
    np = _np()
    db, h = env_db(x, sr)
    if db.size < 3:
        return 0
    floor = db.max() - 22.0
    min_sep = max(1, int(0.09 * sr / h))
    peaks, last = 0, -10 ** 9
    for i in range(1, db.size - 1):
        if db[i] >= floor and db[i] >= db[i - 1] and db[i] > db[i + 1] and (i - last) >= min_sep:
            peaks += 1
            last = i
    return peaks


def f0_track(x, sr=SR, fmin=70.0, fmax=450.0):
    """Crude autocorrelation F0 per 40 ms frame; returns voiced F0 array (Hz)."""
    np = _np()
    w = int(0.04 * sr)
    h = int(0.02 * sr)
    lo, hi = int(sr / fmax), int(sr / fmin)
    out = []
    for i in range(0, max(0, len(x) - w), h):
        f = x[i:i + w]
        e = float(np.sqrt(np.mean(f * f)))
        if e < 1e-3:
            continue
        f = f - f.mean()
        ac = np.correlate(f, f, mode="full")[w - 1:]
        if ac[0] <= 0 or hi >= ac.size:
            continue
        seg = ac[lo:hi]
        if seg.size == 0:
            continue
        k = int(np.argmax(seg)) + lo
        if ac[k] / ac[0] < 0.35:
            continue
        out.append(sr / float(k))
    return np.asarray(out)


def f0_range_semitones(x, sr=SR):
    np = _np()
    f = f0_track(x, sr)
    if f.size < 4:
        return 0.0
    lo, hi = np.percentile(f, 10), np.percentile(f, 90)
    if lo <= 0:
        return 0.0
    return float(12.0 * np.log2(hi / lo))


def max_internal_silence(x, sr=SR):
    segs = speech_segments(x, sr)
    if len(segs) < 2:
        return 0.0
    return max(b[0] - a[1] for a, b in zip(segs, segs[1:]))


# --------------------------------------------------------------------------
# model
# --------------------------------------------------------------------------

def load_model():
    import torch
    torch.set_num_threads(4)
    from chatterbox.mtl_tts import ChatterboxMultilingualTTS
    t0 = time.time()
    m = ChatterboxMultilingualTTS.from_pretrained(device="cpu")
    print("[model] loaded in %.1fs, sr=%d" % (time.time() - t0, m.sr), flush=True)
    return m


REF_TEXT = ("Alright, listen up, challenger. This is the reaction test, and I "
            "will be calling every single one of your results, so do try to "
            "impress me today.")


def cmd_refs(model=None):
    """Generate the neutral reference clip with the default voice, then derive
    GRUFF (pitch/formant down) and SQUEAKY (up) by resampling.  No recordings of
    real people are used anywhere."""
    np = _np()
    neutral = os.path.join(REFS, "neutral.wav")
    if not os.path.exists(neutral):
        model = model or load_model()
        t0 = time.time()
        wav = model.generate(REF_TEXT, language_id="en", exaggeration=0.6,
                             cfg_weight=0.5, temperature=0.7)
        x = wav.squeeze(0).cpu().numpy().astype(np.float64)
        save_wav_f32(neutral, peak_normalise(x, -3.0))
        print("[refs] neutral %.2fs audio in %.1fs" % (len(x) / SR, time.time() - t0), flush=True)
    x, _ = load_wav(neutral)
    for name, factor in (("gruff", 0.82), ("squeaky", 1.28)):
        p = os.path.join(REFS, name + ".wav")
        if not os.path.exists(p):
            save_wav_f32(p, peak_normalise(resample_naive(x, factor), -3.0))
            print("[refs] %s written (factor %.2f)" % (name, factor), flush=True)
    return model


def cmd_generate():
    import torch
    np = _np()
    model = load_model()
    cmd_refs(model)

    total = len(LINES) * len(LANGS) * len(TAKES)
    done = 0
    t_start = time.time()
    for line in LINES:
        char = CHARACTER[line][0]
        ref_path = os.path.join(REFS, REF_FOR[char] + ".wav")
        neu_path = os.path.join(REFS, "neutral.wav")
        for lang in LANGS:
            for ti, (vi, exag, cfg, temp, use_char, seed) in enumerate(TAKES):
                done += 1
                stem = "%s_%s_t%d" % (line, lang, ti)
                wav_path = os.path.join(RAW, stem + ".wav")
                meta_path = os.path.join(RAW, stem + ".json")
                if os.path.exists(wav_path) and os.path.exists(meta_path):
                    print("[%3d/%3d] skip %s" % (done, total, stem), flush=True)
                    continue
                text, syl, canon = VARIANTS[line][lang][vi]
                prompt = ref_path if use_char else neu_path
                torch.manual_seed(seed)
                t0 = time.time()
                try:
                    wav = model.generate(text, language_id=lang,
                                         audio_prompt_path=prompt,
                                         exaggeration=exag, cfg_weight=cfg,
                                         temperature=temp)
                    x = wav.squeeze(0).cpu().numpy().astype(np.float64)
                    err = None
                except Exception as e:                    # noqa: BLE001
                    x = np.zeros(1)
                    err = "%s: %s" % (type(e).__name__, e)
                dt = time.time() - t0
                save_wav_f32(wav_path, x)
                with open(meta_path, "w", encoding="utf-8") as fh:
                    json.dump({"line": line, "lang": lang, "take": ti,
                               "text": text, "syllables": syl, "canonical": canon,
                               "exaggeration": exag, "cfg_weight": cfg,
                               "temperature": temp, "ref": os.path.basename(prompt),
                               "seed": seed, "gen_seconds": round(dt, 1),
                               "audio_seconds": round(len(x) / SR, 2),
                               "error": err}, fh, ensure_ascii=False, indent=1)
                eta = (time.time() - t_start) / done * (total - done) / 60.0
                print("[%3d/%3d] %-28s gen %5.1fs audio %5.2fs  ETA %.0f min %s"
                      % (done, total, stem, dt, len(x) / SR, eta, err or ""), flush=True)
    print("[generate] finished in %.1f min" % ((time.time() - t_start) / 60.0), flush=True)


# --------------------------------------------------------------------------
# selection
# --------------------------------------------------------------------------

def _norm_text(s):
    import re
    s = s.lower()
    s = re.sub(r"[^\w\s]+", " ", s, flags=re.UNICODE)
    return " ".join(s.split())


def _similarity(a, b):
    import difflib
    return difflib.SequenceMatcher(None, _norm_text(a), _norm_text(b)).ratio()


class ASR(object):
    """Optional offline intelligibility check (openai/whisper-small, MIT,
    official HF repo).  Used as a scoring signal / tiebreaker, never as the
    sole rejector -- it is unreliable on one-word utterances."""

    def __init__(self, enabled=True):
        self.pipe = None
        if not enabled:
            return
        try:
            from transformers import pipeline
            t0 = time.time()
            self.pipe = pipeline("automatic-speech-recognition",
                                 model="openai/whisper-small", device="cpu")
            print("[asr] whisper-small loaded in %.1fs" % (time.time() - t0), flush=True)
        except Exception as e:                            # noqa: BLE001
            print("[asr] unavailable (%s) -- falling back to duration/syllable heuristics" % e,
                  flush=True)
            self.pipe = None

    def transcribe(self, x, lang):
        if self.pipe is None:
            return None
        np = _np()
        try:
            r = self.pipe({"array": np.asarray(x, dtype=np.float32), "sampling_rate": SR},
                          generate_kwargs={"language": lang, "task": "transcribe"})
            return (r.get("text") or "").strip()
        except Exception as e:                            # noqa: BLE001
            return "<asr-error:%s>" % type(e).__name__


def score_take(x, meta, asr):
    """Objective checks -> (score, metrics dict).  Higher score is better."""
    np = _np()
    syl = meta["syllables"]
    lo_dur = max(0.30, 0.16 * syl)
    hi_dur = 0.55 * syl + 0.85

    best = None
    for tname, (a, b) in trim_candidates(x).items():
        y = slice_s(x, a, b)
        if y.size < int(0.1 * SR):
            continue
        dur = len(y) / SR
        yn = peak_normalise(y, -3.0)
        m = {
            "trim": tname,
            "dur": round(dur, 2),
            "dur_ok": bool(lo_dur <= dur <= hi_dur),
            "dur_lo": round(lo_dur, 2), "dur_hi": round(hi_dur, 2),
            "peak_db": round(float(20 * np.log10(max(1e-9, np.max(np.abs(y))))), 2),
            "clip_frac": round(float(np.mean(np.abs(y) >= 0.999)), 5),
            "max_gap": round(max_internal_silence(yn), 2),
            "nuclei": syllable_nuclei(yn),
            "f0_range_st": round(f0_range_semitones(yn), 1),
        }
        m["nuclei_err"] = abs(m["nuclei"] - syl)

        s = 0.0
        # duration plausibility (hard-ish)
        if m["dur_ok"]:
            s += 3.0
        else:
            over = (dur - hi_dur) if dur > hi_dur else (lo_dur - dur)
            s -= min(6.0, 2.0 + 2.0 * over)
        # no long internal silence
        s -= min(3.0, 4.0 * max(0.0, m["max_gap"] - 0.30))
        # no clipping
        if m["clip_frac"] > 0.0005:
            s -= 2.0
        # syllable-nuclei agreement
        s -= min(3.0, 0.9 * m["nuclei_err"])
        # expressiveness proxy: F0 range, rewarded up to ~10 semitones
        s += min(2.0, m["f0_range_st"] / 5.0)
        # prefer the canonical wording
        if meta.get("canonical"):
            s += 0.6
        # intelligibility
        txt = asr.transcribe(yn, meta["lang"])
        if txt is not None:
            sim = _similarity(txt, meta["text"])
            m["asr"] = txt
            m["asr_sim"] = round(sim, 3)
            s += 2.5 * sim
        m["score"] = round(s, 2)
        if best is None or s > best["score"]:
            best = m
    return best


def cmd_select(use_asr=True):
    np = _np()
    asr = ASR(use_asr)
    results = {}
    for line in LINES:
        for lang in LANGS:
            cands = []
            for ti in range(len(TAKES)):
                stem = "%s_%s_t%d" % (line, lang, ti)
                wp = os.path.join(RAW, stem + ".wav")
                mp = os.path.join(RAW, stem + ".json")
                if not (os.path.exists(wp) and os.path.exists(mp)):
                    continue
                meta = json.load(open(mp, encoding="utf-8"))
                if meta.get("error"):
                    continue
                x, _ = load_wav(wp)
                if x.size < int(0.15 * SR):
                    continue
                m = score_take(x, meta, asr)
                if m is None:
                    continue
                m.update({k: meta[k] for k in ("take", "text", "syllables", "canonical",
                                               "exaggeration", "cfg_weight", "temperature",
                                               "ref", "seed", "gen_seconds")})
                m["stem"] = stem
                cands.append(m)
                print("  %-30s %s" % (stem, json.dumps(
                    {k: m[k] for k in ("trim", "dur", "nuclei", "nuclei_err", "max_gap",
                                       "f0_range_st", "asr_sim", "score") if k in m},
                    ensure_ascii=False)), flush=True)
            if not cands:
                print("[select] NO USABLE TAKE for %s_%s" % (line, lang), flush=True)
                continue
            cands.sort(key=lambda c: -c["score"])
            best = cands[0]
            results["%s_%s" % (line, lang)] = {"best": best, "all": cands}
            # write chosen take
            x, _ = load_wav(os.path.join(RAW, best["stem"] + ".wav"))
            a, b = trim_candidates(x)[best["trim"]]
            y = peak_normalise(slice_s(x, a, b), -3.0)
            save_wav_pcm16(os.path.join(REC, "%s_%s.wav" % (line, lang)), y)
            # alternates
            for c in cands[1:]:
                xa, _ = load_wav(os.path.join(RAW, c["stem"] + ".wav"))
                aa, bb = trim_candidates(xa)[c["trim"]]
                save_wav_pcm16(os.path.join(ALT, c["stem"] + ".wav"),
                               peak_normalise(slice_s(xa, aa, bb), -3.0))
            print("[select] %s_%s -> take %d (score %.2f)" % (line, lang, best["take"], best["score"]),
                  flush=True)

    json.dump(results, open(os.path.join(ALT, "metrics.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    write_takes_md(results)
    print("[select] done", flush=True)


def write_takes_md(results):
    rows = []
    for line in LINES:
        for lang in LANGS:
            r = results.get("%s_%s" % (line, lang))
            if not r:
                rows.append("| `%s_%s` | %s | — | **MISSING** | | | | | | |"
                            % (line, lang, CANON[line][lang]))
                continue
            b = r["best"]
            rows.append(
                "| `%s_%s` | %s | t%d | %s | %.2f (%.2f–%.2f) | %d/%d | %.2f | %.1f | %s | %s |"
                % (line, lang, CANON[line][lang], b["take"], b["text"], b["dur"],
                   b["dur_lo"], b["dur_hi"], b["nuclei"], b["syllables"], b["max_gap"],
                   b["f0_range_st"],
                   ("%.2f" % b["asr_sim"]) if "asr_sim" in b else "n/a",
                   ("`%s`" % b["asr"].replace("|", "/")[:40]) if "asr" in b else "—"))
    md = [
        "# Neural voice takes (Chatterbox Multilingual)",
        "",
        "Raw, **un-characterised** takes for the audio pipeline's DSP chain.",
        "",
        "- Format: **mono, 24 000 Hz, 16-bit PCM WAV**, silence-trimmed, peak-normalised to **-3 dBFS**.",
        "  Note `synth.py` uses `SR_TONAL = 22050` for voices — **these files are 24 kHz** "
        "(Chatterbox's native rate, not resampled so the DSP chain gets the original). Resample on load.",
        "- Chosen takes: `tool/audio/recordings/<line>_<lang>.wav`",
        "- Rejected takes: `tool/audio/recordings/_alternates/` (git-ignored), raw untrimmed in `_alternates/raw/`",
        "- Regenerate: see `README_neural_voices.md`",
        "",
        "## Chosen take per line",
        "",
        "| resource | canonical text | take | prompt text used | duration s (plausible range) | "
        "syllable nuclei / expected | max internal silence s | F0 range (semitones) | ASR similarity | ASR transcript |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ] + rows + [
        "",
        "## Metric meanings",
        "",
        "- **duration**: plausible range is `0.16·syllables … 0.55·syllables + 0.85` s.",
        "- **syllable nuclei**: energy-envelope peaks ≥ −22 dB below frame peak, ≥ 90 ms apart — "
        "a proxy for \"the model said the right number of things\".",
        "- **max internal silence**: longest gap between active-speech segments after trimming; "
        "large values indicate a hallucinated second utterance.",
        "- **F0 range**: 10th–90th percentile of the autocorrelation pitch track, in semitones — "
        "expressiveness proxy (flat reads score near 0).",
        "- **ASR similarity**: `difflib` ratio of a `whisper-small` transcript to the prompt text. "
        "Used as a scoring signal only, never as a sole rejector (whisper-small is weak on "
        "one-word utterances, especially in Russian).",
        "",
        "Full per-take metrics: `_alternates/metrics.json`.",
    ]
    with open(os.path.join(REC, "TAKES.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")


# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["refs", "generate", "select"])
    ap.add_argument("--no-asr", action="store_true")
    a = ap.parse_args()
    if a.command == "refs":
        cmd_refs()
    elif a.command == "generate":
        cmd_generate()
    else:
        cmd_select(use_asr=not a.no_asr)


if __name__ == "__main__":
    main()
