"""Character voices + non-verbal monster vocalisations for Reaction Speed.

    python tool/audio/voices_post.py

Stage A - character voices (localised)
    Raw take -> character DSP chain -> app/src/main/res/raw{,-de,-ru}/.
    The raw take is taken from, in priority order:
      1. tool/audio/recordings/<id>_<lang>.wav   (real recording or neural TTS)
      2. tool/audio/_raw_tts/<id>_<lang>.wav     (SAPI, produced by voices.ps1)
    Any sample rate / channel count is accepted and converted.

    Three characters, each a different pitch/formant/rasp treatment:
      GRUFF   - hoarse big monster (pitch down, formants further down, growl AM,
                subharmonic, breath, soft-clip drive, dark EQ)
      SQUEAKY - tiny hyper monster (pitch way up, formants up LESS than pitch so
                it stays a voice and not a chipmunk artefact, fast vibrato)
      SHOUTY  - excited announcer (moderate pitch up, compression, rising
                exclamation glide, presence boost, light saturation)

    `dsp_amount` (0..1) scales the whole treatment. Already-expressive sources
    (real recordings / neural TTS) get RECORDING_DSP_AMOUNT and no extra time
    warping, so nothing is double-stretched.

Stage B - non-verbal monster vocalisations (language independent, raw/ only)
    Synthesised from scratch with a source-filter model: jittered glottal pulse
    train + breath noise -> a bank of moving formant resonators.

This script is standalone: it never regenerates SFX or music.
"""

from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth as S

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
RAW_TTS = os.path.join(HERE, "_raw_tts")
RECORDINGS = os.path.join(HERE, "recordings")

SR = S.SR_TONAL          # 22050 Hz output for all voice assets
TTS_DSP_AMOUNT = 1.0     # flat SAPI needs the full treatment
RECORDING_DSP_AMOUNT = 0.55   # expressive source: lighter touch, no time warp

VOICE_PEAK_DB = -3.0
VOICE_RMS_DB = -17.0     # voices sit a little hotter than SFX; duck music instead


# ==========================================================================
# Stage A - character chains
# ==========================================================================

def _growl(x: np.ndarray, sr: int, rate_hz: float, depth: float) -> np.ndarray:
    """Amplitude-modulated rasp: a rough sub-audio tremolo, slightly jittered."""
    n = x.size
    t = np.arange(n) / sr
    jit = 1.0 + 0.08 * np.sin(2 * np.pi * 3.7 * t)
    m = 0.5 + 0.5 * np.sin(2 * np.pi * rate_hz * jit * t)
    m = m ** 1.6                      # rougher than a sine
    return x * (1.0 - depth + depth * m)


def _breath(x: np.ndarray, sr: int, amount: float, lo=1200.0, hi=5200.0) -> np.ndarray:
    """Noise excited by the signal's own envelope - adds air / hoarseness."""
    w = max(1, int(0.008 * sr))
    env = np.convolve(np.abs(x), np.ones(w) / w, mode="same")
    env /= max(1e-9, env.max())
    nz = S.bandpass(S.rng().standard_normal(x.size), lo, hi, sr, 2)
    return x + amount * nz * env


def _subharmonic(x: np.ndarray, sr: int, amount: float) -> np.ndarray:
    if amount <= 0.001:
        return x
    sub = S.pitch_shift(x, sr, -12.0)
    sub = S.lowpass(S.pad_to(sub, x.size), 900.0, sr, 2)
    return x + amount * sub


def char_gruff(x: np.ndarray, sr: int, amt: float, warp: bool) -> np.ndarray:
    """Hoarse, lumbering monster.

    restore=0: the tape shift down already makes it ~23 % slower, which is
    exactly the lumbering feel we want and costs no stretch artefacts.
    """
    y = S.tape_shift(x, sr, -3.5 * amt, -6.5 * amt, restore=0.0)
    if warp:
        y = S.stretch_loudest(y, sr, 1.0 + 0.25 * amt)
    y = _subharmonic(y, sr, 0.26 * amt)
    y = _growl(y, sr, 34.0, 0.36 * amt)
    y = _breath(y, sr, 0.05 * amt, 900.0, 4200.0)
    y = S.soft_clip(y * (1.0 + 0.8 * amt), 1.5)
    y = S.peaking(y, 240.0, 4.5 * amt, 0.9, sr)
    y = S.peaking(y, 2600.0, -4.0 * amt, 1.0, sr)
    y = S.lowpass(y, 6500, sr, 2)
    # the growl AM digs deep amplitude notches, which leaves a high crest
    # factor and a line that measures 4-5 dB quieter than the other characters;
    # squash it back so all three characters sit at the same perceived level
    y = S.compress(y, sr, thresh_db=-20.0, ratio=3.5, attack=0.006,
                   release=0.10, makeup_db=4.0 * amt)
    # a downward grumble at the end (length left free - small rate change)
    return S.pitch_contour(y, sr, np.concatenate(
        [np.zeros(6), np.linspace(0, -2.0 * amt, 4)]), keep_length=False)


def char_squeaky(x: np.ndarray, sr: int, amt: float, warp: bool) -> np.ndarray:
    """Tiny hyper monster: formants raised much LESS than the pitch.

    restore=0.55 puts back about half the tape speed-up, so it stays a fast,
    twitchy little voice while the WSOLA factor stays around 1.3 (mild).
    """
    y = S.tape_shift(x, sr, 9.5 * amt, 5.0 * amt, restore=0.55)
    y = S.vibrato(y, sr, rate=7.5, depth_ms=0.8 * amt)
    y = _breath(y, sr, 0.035 * amt, 2500.0, 7500.0)
    y = S.peaking(y, 3200.0, 3.5 * amt, 1.1, sr)
    y = S.peaking(y, 500.0, -3.5 * amt, 0.8, sr)
    y = S.highpass(y, 220.0, sr, 2)
    y = S.soft_clip(y * (1.0 + 0.4 * amt), 1.2)
    return S.pitch_contour(y, sr, np.concatenate(
        [np.zeros(7), np.linspace(0, 2.5 * amt, 3)]), keep_length=False)


def char_shouty(x: np.ndarray, sr: int, amt: float, warp: bool) -> np.ndarray:
    """Excited announcer: compressed, bright, exclamation glide."""
    y = S.tape_shift(x, sr, 4.5 * amt, 2.0 * amt, restore=0.5)
    y = S.compress(y, sr, thresh_db=-20.0, ratio=4.0, attack=0.004,
                   release=0.08, makeup_db=4.0 * amt)
    if warp:
        y = S.stretch_loudest(y, sr, 1.0 + 0.20 * amt)
    y = _breath(y, sr, 0.025 * amt, 2000.0, 6500.0)
    y = S.peaking(y, 3000.0, 4.5 * amt, 0.9, sr)
    y = S.peaking(y, 900.0, 2.0 * amt, 1.2, sr)
    y = S.highpass(y, 140.0, sr, 2)
    y = S.soft_clip(y * (1.0 + 0.45 * amt), 1.4)
    # rise into the exclamation, tiny drop on the very tail
    curve = np.concatenate([np.linspace(0, 1.6 * amt, 7), np.linspace(1.6 * amt, 0.6 * amt, 3)])
    return S.pitch_contour(y, sr, curve, keep_length=False)


CHARACTERS = {"GRUFF": char_gruff, "SQUEAKY": char_squeaky, "SHOUTY": char_shouty}


def process_line(path: str, character: str, amount: float, warp: bool) -> np.ndarray:
    sr_in, x = S.read_wav(path)
    x = S.resample_to(x, sr_in, SR)
    x = S.dc_block(x, SR, 70.0)
    x = S.trim_silence(x, SR, thresh_db=-42.0, pad_ms=10.0)
    x = S.normalize(x, -6.0)
    y = CHARACTERS[character](x, SR, amount, warp)
    y = S.dc_block(y, SR, 80.0)
    y = S.trim_silence(y, SR, thresh_db=-42.0, pad_ms=12.0)
    y = S.fade(y, SR, 0.006, 0.020)
    return S.loudness_match(y, SR, VOICE_RMS_DB, VOICE_PEAK_DB)


def stage_a():
    with open(os.path.join(HERE, "voice_lines.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    written = []
    for lang, li in cfg["languages"].items():
        outdir = os.path.join(RES, li["dir"])
        os.makedirs(outdir, exist_ok=True)
        for line in cfg["lines"]:
            lid = line["id"]
            rec = os.path.join(RECORDINGS, f"{lid}_{lang}.wav")
            tts = os.path.join(RAW_TTS, f"{lid}_{lang}.wav")
            if os.path.exists(rec):
                src, amount, warp, tag = rec, RECORDING_DSP_AMOUNT, False, "rec"
            elif os.path.exists(tts):
                src, amount, warp, tag = tts, TTS_DSP_AMOUNT, True, "tts"
            else:
                print(f"  MISSING raw take for {lid}/{lang} - skipped")
                continue
            amount = float(line.get("dsp_amount", amount))
            y = process_line(src, line["character"], amount, warp)
            p = S.write_wav(os.path.join(outdir, f"{lid}.wav"), y, SR)
            written.append((p, y, tag, line["character"]))
            print(f"  {li['dir']:7s} {lid:18s} {line['character']:8s} {tag}  "
                  f"{y.size/SR:5.2f}s  {os.path.getsize(p)/1024:7.1f} KB")
    return written


# ==========================================================================
# Stage B - non-verbal monster vocalisations (source-filter synthesis)
# ==========================================================================

VOWELS = {
    "a":  [(730, 130), (1090, 170), (2440, 260), (3400, 400)],
    "e":  [(530, 120), (1840, 180), (2480, 260), (3500, 400)],
    "i":  [(270, 100), (2290, 200), (3010, 300), (3600, 420)],
    "o":  [(570, 120), (840, 160), (2410, 260), (3300, 400)],
    "u":  [(300, 100), (870, 150), (2240, 260), (3200, 400)],
    "uh": [(520, 130), (1190, 180), (2390, 260), (3300, 400)],
}
F_GAINS = [1.0, 0.65, 0.32, 0.16]


def glottal_source(f0: np.ndarray, n: int, sr: int, jitter: float = 0.02,
                   shimmer: float = 0.10, breath: float = 0.12,
                   rasp: float = 0.0) -> np.ndarray:
    """Rosenberg-ish glottal pulse train with jitter/shimmer plus breath noise."""
    rng = S.rng()
    f = S.pad_to(np.asarray(f0, float), n)
    f = f * (1.0 + jitter * S.lowpass(rng.standard_normal(n), 28.0, sr, 2) * 6.0)
    f = np.clip(f, 40.0, 900.0)
    ph = (np.cumsum(f) / sr) % 1.0
    # Rosenberg glottal flow: open 60 %, closing 20 %
    op, cl = 0.60, 0.20
    g = np.zeros(n)
    m1 = ph < op
    g[m1] = 3.0 * (ph[m1] / op) ** 2 - 2.0 * (ph[m1] / op) ** 3
    m2 = (ph >= op) & (ph < op + cl)
    u = (ph[m2] - op) / cl
    g[m2] = 1.0 - u ** 2
    g = np.gradient(g) * sr / np.maximum(1.0, np.mean(f))   # differentiated flow
    if shimmer:
        amp = 1.0 + shimmer * S.lowpass(rng.standard_normal(n), 22.0, sr, 2) * 6.0
        g = g * amp
    if rasp:
        g = _growl(g, sr, 48.0, rasp)
        g = g + rasp * 0.5 * S.lowpass(np.sign(g) * np.abs(g) ** 0.6, 1800, sr, 2)
    if breath:
        g = g + breath * S.bandpass(rng.standard_normal(n), 800, 7000, sr, 2)
    return g


def monster(dur: float, f0_pts, vowel_pts, sr: int = SR, formant_scale: float = 1.0,
            jitter: float = 0.02, shimmer: float = 0.10, breath: float = 0.12,
            rasp: float = 0.0, attack: float = 0.012, decay: float = 0.06,
            curve: float = 1.0) -> np.ndarray:
    """Synthesise a vocalisation.

    f0_pts    : list of (time_fraction, hz) - the pitch contour
    vowel_pts : list of (time_fraction, vowel_key) - morphs between vowels
    """
    n = int(round(dur * sr))
    u = np.linspace(0, 1, n)
    f0 = np.interp(u, [p[0] for p in f0_pts], [p[1] for p in f0_pts])
    src = glottal_source(f0, n, sr, jitter, shimmer, breath, rasp)

    # per-sample formant trajectories, morphing between the vowel keyframes
    times = [p[0] for p in vowel_pts]
    out = np.zeros(n)
    n_f = len(F_GAINS)
    for k in range(n_f):
        fk = np.interp(u, times, [VOWELS[p[1]][k][0] for p in vowel_pts]) * formant_scale
        bk = np.interp(u, times, [VOWELS[p[1]][k][1] for p in vowel_pts]) * formant_scale
        # piecewise-constant banks: cheap, and the segments are short
        seg = max(1, n // 24)
        band = np.zeros(n)
        for a in range(0, n, seg):
            b = min(n, a + seg)
            f_c = float(np.mean(fk[a:b]))
            bw = float(np.mean(bk[a:b]))
            lo = max(60.0, f_c - bw)
            hi = min(0.45 * sr, f_c + bw)
            if hi <= lo * 1.02:
                hi = min(0.45 * sr, lo * 1.15)
            piece = S.bandpass(src[max(0, a - seg):b], lo, hi, sr, 2)
            band[a:b] = piece[-(b - a):]
        out += F_GAINS[k] * band

    env = S.adsr(dur, sr, attack, decay, 0.85, min(0.22, dur * 0.4), curve)
    out *= env
    out = S.soft_clip(out * 1.3, 1.3)
    return S.dc_block(out, sr, 70.0)


def build_vox():
    """The non-verbal set. Names are language independent -> raw/ only."""
    v = {}

    # --- ouch / hit reactions -----------------------------------------
    v["vox_ouch_1"] = monster(          # "ow!"
        0.40, [(0, 430), (0.12, 520), (1, 300)],
        [(0, "a"), (0.35, "a"), (1, "u")], formant_scale=1.08,
        jitter=0.03, breath=0.10, rasp=0.10, attack=0.006, decay=0.05, curve=1.3)
    v["vox_ouch_2"] = monster(          # "oof!" - low, winded
        0.32, [(0, 230), (0.2, 250), (1, 165)],
        [(0, "u"), (0.5, "o"), (1, "u")], formant_scale=0.88,
        jitter=0.035, breath=0.20, rasp=0.30, attack=0.005, decay=0.045, curve=1.4)
    v["vox_ouch_3"] = monster(          # "eek!" - tiny and panicked
        0.30, [(0, 620), (0.25, 900), (1, 780)],
        [(0, "i"), (1, "i")], formant_scale=1.30,
        jitter=0.05, shimmer=0.16, breath=0.08, attack=0.004, decay=0.04, curve=1.2)
    v["vox_ouch_4"] = monster(          # "bleh!" - tongue out, descending
        0.42, [(0, 340), (0.15, 330), (1, 175)],
        [(0, "e"), (0.4, "e"), (1, "a")], formant_scale=1.0,
        jitter=0.06, shimmer=0.18, breath=0.16, rasp=0.45,
        attack=0.005, decay=0.06, curve=1.3)

    # --- taunting laughs ----------------------------------------------
    def laugh(pitches, seg=0.135, vowel="e", scale=1.0, rasp=0.15, gap=0.035):
        sr = SR
        total = len(pitches) * (seg + gap)
        out = np.zeros(int(round(total * sr)))
        for i, p in enumerate(pitches):
            syll = monster(seg, [(0, p * 1.12), (0.3, p), (1, p * 0.92)],
                           [(0, vowel), (1, vowel)],
                           formant_scale=scale, jitter=0.04, shimmer=0.14,
                           breath=0.16, rasp=rasp, attack=0.006, decay=0.03, curve=1.5)
            S.place(out, syll, int(round(i * (seg + gap) * sr)))
        return out

    v["vox_laugh_1"] = laugh([330, 300, 275, 250], vowel="e", scale=1.0, rasp=0.25)
    v["vox_laugh_2"] = laugh([520, 560, 600], seg=0.115, vowel="i", scale=1.25, rasp=0.10)

    # --- cheer / disappointment ---------------------------------------
    v["vox_yay"] = monster(             # "yaaay!"
        0.85, [(0, 380), (0.15, 430), (0.6, 470), (1, 560)],
        [(0, "i"), (0.18, "a"), (1, "a")], formant_scale=1.15,
        jitter=0.025, shimmer=0.10, breath=0.09, attack=0.020, decay=0.10, curve=1.1)
    v["vox_aww"] = monster(             # "awww"
        0.80, [(0, 300), (0.25, 280), (1, 190)],
        [(0, "a"), (0.4, "o"), (1, "u")], formant_scale=0.98,
        jitter=0.03, shimmer=0.12, breath=0.14, rasp=0.12,
        attack=0.030, decay=0.10, curve=1.2)

    # --- optional pop-up chirps (see notes: NOT for the target onset) --
    v["vox_pop_hello_1"] = monster(     # "hup!"
        0.24, [(0, 400), (0.3, 560), (1, 520)],
        [(0, "uh"), (0.4, "u"), (1, "uh")], formant_scale=1.22,
        jitter=0.03, breath=0.10, attack=0.005, decay=0.035, curve=1.4)
    v["vox_pop_hello_2"] = monster(     # "boo!"
        0.26, [(0, 300), (0.25, 360), (1, 330)],
        [(0, "u"), (1, "o")], formant_scale=1.10,
        jitter=0.03, breath=0.12, rasp=0.10, attack=0.006, decay=0.04, curve=1.3)

    outdir = os.path.join(RES, "raw")
    os.makedirs(outdir, exist_ok=True)
    written = []
    for name, y in v.items():
        y = S.fade(y, SR, 0.004, 0.018)
        y = S.trim_silence(y, SR, -45.0, 8.0)
        y = S.loudness_match(y, SR, -19.0, -3.0)
        p = S.write_wav(os.path.join(outdir, f"{name}.wav"), y, SR)
        written.append(p)
        print(f"  raw     {name:18s} {'synth':8s} vox  {y.size/SR:5.2f}s  "
              f"{os.path.getsize(p)/1024:7.1f} KB")
    return written


def main():
    S.reseed(11)
    print("Stage A - character voices")
    a = stage_a()
    print("Stage B - monster vocalisations")
    b = build_vox()
    total = sum(os.path.getsize(p) for p, *_ in a) + sum(os.path.getsize(p) for p in b)
    print(f"\n{len(a)} localized voice files + {len(b)} vox files, "
          f"{total/1024:.1f} KB total")


if __name__ == "__main__":
    main()
