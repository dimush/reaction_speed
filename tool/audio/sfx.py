"""Cartoon SFX + jingles for Reaction Speed.

    python tool/audio/sfx.py

Writes 16-bit PCM WAV into app/src/main/res/raw/.

Levels: every file is peak-limited to about -3 dBFS and, where it matters,
loudness-matched on a 300 Hz-6 kHz weighted RMS so one cue does not jump out
over the others. The three target pops share ONE gain so their onsets are
exactly equally loud (reaction-time fairness).
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from synth import *  # noqa: F401,F403
import synth as S

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "..", "app", "src", "main", "res", "raw")
OUT = os.path.normpath(OUT)

SR_T = S.SR_SFX     # transient SFX
SR_M = S.SR_TONAL   # tonal stingers

# perceived-loudness targets (weighted RMS, dBFS)
L_POP = -21.0
L_HIT = -18.5
L_SOFT = -24.0
L_JINGLE = -19.0


# --------------------------------------------------------------------------
# 1. target pops - identical onset, identical gain
# --------------------------------------------------------------------------

def target_pops():
    """Three 'bloop' variants that are byte-identical for the first 30 ms."""
    sr = SR_T
    dur = 0.30
    n = int(round(dur * sr))
    n_common = int(round(0.030 * sr))          # shared onset window
    n_xf = int(round(0.014 * sr))              # crossfade starts AFTER the onset

    # shared pitch contour for the onset: fast rise, the classic "bloop"
    f_common = S.sweep(180.0, 760.0, n_common / sr, sr, "exp")
    # shared amplitude: sub-5 ms attack, then a gentle hold
    env_common = np.concatenate([
        np.linspace(0.0, 1.0, int(round(0.0025 * sr))) ** 0.7,
        np.full(n_common - int(round(0.0025 * sr)), 1.0) * np.exp(
            -np.arange(n_common - int(round(0.0025 * sr))) / (0.09 * sr)),
    ])[:n_common]

    tails = [
        dict(f1=1180.0, decay=0.055, wob=0.0, ring=2.0),    # plain bloop
        dict(f1=980.0, decay=0.070, wob=26.0, ring=3.0),    # slight wobble
        dict(f1=1320.0, decay=0.048, wob=0.0, ring=1.5),    # brighter, shorter
    ]

    outs = []
    for t in tails:
        # full contour: common head, then this variant's continuation
        n_tail = n - n_common
        f_tail = S.sweep(f_common[-1], t["f1"], n_tail / sr, sr, "exp")
        if t["wob"]:
            f_tail = f_tail * (1.0 + 0.05 * np.sin(
                2 * np.pi * t["wob"] * np.arange(n_tail) / sr))
        f = np.concatenate([f_common, f_tail])

        env_tail = env_common[-1] * np.exp(-np.arange(n_tail) / (t["decay"] * sr))
        env_tail *= np.linspace(1.0, 0.0, n_tail) ** 0.6
        env = np.concatenate([env_common, env_tail])

        body = 0.85 * np.sin(S.phase_of(f, n, sr)) + 0.15 * np.sin(2 * S.phase_of(f, n, sr))
        body *= env

        # tiny resonant "lip" ring, variant-dependent, well past the onset
        ring = np.zeros(n)
        r = S.sine(t["f1"] * t["ring"], (n - n_common) / sr, sr) * \
            np.exp(-np.arange(n - n_common) / (0.018 * sr)) * 0.10
        ring[n_common:] = r

        y = body + ring
        y = S.soft_clip(y, 1.1)
        y = S.dc_block(y, sr)
        y = S.fade(y, sr, fin=0.0, fout=0.012)
        outs.append(y)

    # make the onsets bit-identical: force the shared head back in, then
    # crossfade each variant in over the window that follows it
    head = outs[0][:n_common].copy()
    xf = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, n_xf))
    for i, y in enumerate(outs):
        y[:n_common] = head
        seg = y[n_common:n_common + n_xf]
        y[n_common:n_common + n_xf] = head[-1] * (1 - xf) * 0.0 + seg * xf + \
            outs[0][n_common:n_common + n_xf] * (1 - xf)
        outs[i] = y

    outs = S.normalize_group(outs, -3.0)
    # one shared loudness trim on top of the shared peak gain
    ref = S.bandpass(outs[0], 300, 6000, sr, 2)
    g = S.db2lin(L_POP) / max(1e-9, float(np.sqrt(np.mean(ref ** 2))))
    g = min(g, S.db2lin(-3.0) / max(1e-9, max(float(np.max(np.abs(o))) for o in outs)))
    return [o * g for o in outs]


# --------------------------------------------------------------------------
# 2. hits
# --------------------------------------------------------------------------

def hit_bonk():
    """Wooden 'bonk' - FM mallet with a fast pitch drop."""
    sr, dur = SR_T, 0.32
    f = S.sweep(520.0, 190.0, dur, sr, "exp")
    idx = S.perc_env(dur, sr, 0.001, 0.035) * 7.0
    y = S.fm(f, 2.02, idx, dur, sr) * S.perc_env(dur, sr, 0.0015, 0.075, 1.2)
    nc = int(round(0.006 * sr))
    click = S.noise(0.006, sr) * np.exp(-np.arange(nc) / (0.0012 * sr))
    click = S.bandpass(click, 1500, 6000, sr, 2) * 0.5
    y = S.mix(y, S.pad_to(click, y.size))
    y = S.soft_clip(y, 1.4)
    return S.fade(y, sr, 0.0, 0.02)


def hit_boing():
    """Cartoon spring 'boing' - vibrato'd sine with a big downward sweep."""
    sr, dur = SR_T, 0.45
    n = int(round(dur * sr))
    t = np.arange(n) / sr
    base = S.sweep(900.0, 210.0, dur, sr, "exp")
    wob = 1.0 + 0.30 * np.sin(2 * np.pi * 21.0 * t) * np.exp(-t / 0.16)
    f = base * wob
    env = S.perc_env(dur, sr, 0.0012, 0.11, 1.4)
    y = (0.8 * np.sin(S.phase_of(f, n, sr)) + 0.2 * S.tri(f, dur, sr)) * env
    y = S.lowpass(y, 5000, sr, 2)
    return S.fade(y, sr, 0.0, 0.02)


def hit_squeak():
    """Rubber-toy squeak - high wobbling tone through a narrow band."""
    sr, dur = SR_T, 0.30
    n = int(round(dur * sr))
    t = np.arange(n) / sr
    f = S.sweep(1450.0, 2100.0, dur, sr, "log") * (1 + 0.10 * np.sin(2 * np.pi * 33 * t))
    y = S.square(f, dur, sr, 0.42) * S.perc_env(dur, sr, 0.003, 0.085, 1.5)
    y = S.bandpass(y, 900, 4200, sr, 2)
    y += 0.12 * S.bandpass(S.noise(dur, sr), 2500, 6500, sr, 2) * S.perc_env(dur, sr, 0.004, 0.05)
    y = S.soft_clip(y, 1.3)
    return S.fade(y, sr, 0.001, 0.02)


def hit_splat():
    """Wet 'splat' - noise burst with a falling low-pass plus a body thump."""
    sr, dur = SR_T, 0.28
    n = int(round(dur * sr))
    nz = S.noise(dur, sr)
    cut = S.sweep(5200.0, 380.0, dur, sr, "exp")
    y = S.sv_lowpass(nz, cut, sr, q=1.4) * S.perc_env(dur, sr, 0.0015, 0.045, 1.2)
    thump = S.sine(S.sweep(170.0, 62.0, dur, sr, "exp"), dur, sr) * \
        S.perc_env(dur, sr, 0.002, 0.05, 1.0) * 0.8
    y = S.mix(y * 1.6, thump)
    y = S.soft_clip(y, 1.5)
    return S.fade(y, sr, 0.0, 0.02)


def hit_bwoop():
    """'Bwoop' - pulse wave rising then dipping, vowel-ish resonance."""
    sr, dur = SR_T, 0.34
    n = int(round(dur * sr))
    u = np.linspace(0, 1, n)
    f = 260.0 * (1.0 + 2.2 * np.sin(np.pi * u ** 0.75))
    y = S.pulse_bl(f, dur, sr, 0.3) * S.perc_env(dur, sr, 0.0025, 0.10, 1.1)
    y = S.formant_bank(y, [(700, 180, 1.0), (1220, 260, 0.7), (2600, 420, 0.3)], sr)
    y = S.soft_clip(y * 1.4, 1.3)
    return S.fade(y, sr, 0.001, 0.02)


# --------------------------------------------------------------------------
# 3. misc SFX
# --------------------------------------------------------------------------

def sfx_miss():
    """Soft dull thud - no sting, the player already feels bad."""
    sr, dur = SR_T, 0.26
    y = S.sine(S.sweep(150.0, 58.0, dur, sr, "exp"), dur, sr) * \
        S.perc_env(dur, sr, 0.004, 0.06, 1.3)
    nz = S.lowpass(S.noise(dur, sr), 420, sr, 3) * S.perc_env(dur, sr, 0.003, 0.028) * 0.5
    y = S.mix(y, nz)
    y = S.lowpass(y, 900, sr, 2)
    return S.fade(y, sr, 0.002, 0.03)


def sfx_false_start():
    """Comedic descending 'wah-wah-waaah' (sad trombone-ish) + a buzz tail."""
    sr = SR_M
    dur = 0.88
    n = int(round(dur * sr))
    out = np.zeros(n)
    steps = [(S.note("a3"), 0.00, 0.22), (S.note("g3"), 0.20, 0.22),
             (S.note("f3"), 0.40, 0.46)]
    for i, (f0, t0, ln) in enumerate(steps):
        m = int(round(ln * sr))
        u = np.linspace(0, 1, m)
        f = f0 * (1.0 - 0.055 * u if i == 2 else 1.0 - 0.02 * u)
        v = S.saw(f, ln, sr)
        # "wah": a resonant low-pass that opens and closes on each note
        cut = 500 + 1700 * np.sin(np.pi * np.clip(u * 1.1, 0, 1)) ** 1.2
        v = S.sv_lowpass(v, cut, sr, q=2.6)
        v *= S.adsr(ln, sr, 0.02, 0.06, 0.75, 0.10, 1.6)
        if i == 2:  # final note gets a lip wobble
            v *= 1.0 + 0.12 * np.sin(2 * np.pi * 6.5 * np.arange(m) / sr)
        S.place(out, v, int(t0 * sr))
    out = S.soft_clip(out * 1.2, 1.4)
    out = S.reverb(out, sr, room=0.35, wet=0.13, tail=0.0)
    return S.fade(out[:n], sr, 0.003, 0.05)


def sfx_ui_click():
    """Tiny tick - short and polite, must never fatigue."""
    sr, dur = SR_T, 0.045
    n = int(round(dur * sr))
    y = S.sine(S.sweep(2100.0, 1400.0, dur, sr, "exp"), dur, sr) * \
        S.perc_env(dur, sr, 0.0008, 0.006, 1.0)
    nz = S.bandpass(S.noise(dur, sr), 2500, 8000, sr, 2) * \
        S.perc_env(dur, sr, 0.0005, 0.0035) * 0.6
    y = S.mix(y, nz)
    return S.fade(y, sr, 0.0, 0.006)


def _bell(f0, dur, sr, bright=1.0, decay=0.35):
    """FM bell/marimba voice used by the fanfares."""
    idx = S.perc_env(dur, sr, 0.001, 0.05) * (3.2 * bright)
    y = S.fm(f0, 3.01, idx, dur, sr) * S.perc_env(dur, sr, 0.002, decay, 1.1)
    y += 0.35 * S.sine(f0, dur, sr) * S.perc_env(dur, sr, 0.003, decay * 1.3, 1.1)
    return y


def sfx_series_finish():
    """Short 'ta-da' - a V->I lift with a bright chord."""
    sr, dur = SR_M, 1.15
    n = int(round(dur * sr))
    out = np.zeros(n)
    # "ta" (dominant) then "daa" (tonic chord)
    for f in (S.note("d4"), S.note("a4")):
        S.place(out, _bell(f, 0.22, sr, 1.0, 0.16) * 0.5, 0)
    for f in (S.note("g3"), S.note("d4"), S.note("g4"), S.note("b4")):
        S.place(out, _bell(f, 0.9, sr, 1.2, 0.34) * 0.42, int(0.20 * sr))
    shimmer = S.bandpass(S.noise(0.5, sr), 5000, 9000, sr, 2) * \
        S.perc_env(0.5, sr, 0.01, 0.12) * 0.09
    S.place(out, shimmer, int(0.20 * sr))
    out = S.reverb(out, sr, room=0.45, wet=0.18, tail=0.0)[:n]
    return S.fade(out, sr, 0.002, 0.08)


def sfx_new_record():
    """Sparkly rising arpeggio fanfare (G major, ~2 s)."""
    sr, dur = SR_M, 1.95
    n = int(round(dur * sr))
    out = np.zeros(n)
    arp = ["g4", "b4", "d5", "g5", "b5", "d6", "g6"]
    for i, nm in enumerate(arp):
        t0 = 0.045 * i
        S.place(out, _bell(S.note(nm), 0.8, sr, 1.3, 0.30) * (0.30 + 0.03 * i), int(t0 * sr))
    # landing chord
    for f in ("g4", "b4", "d5", "g5"):
        S.place(out, _bell(S.note(f), 1.2, sr, 1.1, 0.45) * 0.30, int(0.42 * sr))
    # sparkle dust
    for i in range(14):
        t0 = 0.30 + 0.09 * i + 0.02 * float(S.rng().random())
        if t0 > dur - 0.2:
            break
        f = S.note("g6") * (2.0 ** (S.rng().integers(0, 5) / 12.0))
        S.place(out, S.sine(f, 0.16, sr) * S.perc_env(0.16, sr, 0.002, 0.04) * 0.10,
                int(t0 * sr))
    out = S.reverb(out, sr, room=0.5, wet=0.22, tail=0.0)[:n]
    return S.fade(out, sr, 0.002, 0.12)


# --------------------------------------------------------------------------
# 4. result jingles, tier 1 (sad) .. tier 5 (triumphant)
# --------------------------------------------------------------------------

def _pluck(f0, dur, sr, gain=1.0, duty=0.3, decay=0.20):
    y = S.pulse_bl(f0, dur, sr, duty) * S.perc_env(dur, sr, 0.004, decay, 1.1)
    y = S.lowpass(y, 5200, sr, 2)
    return y * gain


def jingle_tier_1():
    """Sad trombone: three descending slurred notes, minor, sour."""
    sr, dur = SR_M, 1.50
    n = int(round(dur * sr))
    out = np.zeros(n)
    notes = [("e3", 0.00, 0.30), ("d3", 0.28, 0.30), ("c3", 0.56, 0.85)]
    for i, (nm, t0, ln) in enumerate(notes):
        m = int(round(ln * sr))
        u = np.linspace(0, 1, m)
        f = S.note(nm) * (1.0 - (0.06 if i == 2 else 0.015) * u)
        v = S.saw(f, ln, sr)
        cut = 420 + 1300 * np.sin(np.pi * np.clip(u * 1.05, 0, 1)) ** 1.3
        v = S.sv_lowpass(v, cut, sr, q=2.4)
        v *= S.adsr(ln, sr, 0.03, 0.08, 0.7, 0.16, 1.5)
        if i == 2:
            v *= 1.0 + 0.14 * np.sin(2 * np.pi * 5.5 * np.arange(m) / sr)
        S.place(out, v * 0.6, int(t0 * sr))
    out = S.reverb(out, sr, room=0.4, wet=0.15, tail=0.0)[:n]
    return S.fade(out, sr, 0.004, 0.09)


def jingle_tier_2():
    """Neutral-friendly: a rising third, small and shy (C major)."""
    sr, dur = SR_M, 1.20
    n = int(round(dur * sr))
    out = np.zeros(n)
    for nm, t0, ln in [("c4", 0.00, 0.30), ("e4", 0.16, 0.34), ("g4", 0.32, 0.80)]:
        S.place(out, _pluck(S.note(nm), ln, sr, 0.5, 0.35, 0.22), int(t0 * sr))
    S.place(out, _bell(S.note("c5"), 0.7, sr, 0.9, 0.26) * 0.28, int(0.46 * sr))
    out = S.reverb(out, sr, room=0.4, wet=0.15, tail=0.0)[:n]
    return S.fade(out, sr, 0.003, 0.08)


def jingle_tier_3():
    """Positive: bouncy major arpeggio with a cheeky upper neighbour (F major)."""
    sr, dur = SR_M, 1.45
    n = int(round(dur * sr))
    out = np.zeros(n)
    seq = [("f4", 0.00), ("a4", 0.11), ("c5", 0.22), ("f5", 0.33), ("e5", 0.44), ("f5", 0.52)]
    for nm, t0 in seq:
        S.place(out, _pluck(S.note(nm), 0.6, sr, 0.45, 0.3, 0.22), int(t0 * sr))
    for nm in ("f3", "c4", "f4", "a4"):
        S.place(out, _bell(S.note(nm), 0.85, sr, 1.0, 0.32) * 0.26, int(0.52 * sr))
    S.place(out, _pluck(S.note("f2"), 0.4, sr, 0.5, 0.5, 0.16), 0)
    out = S.reverb(out, sr, room=0.45, wet=0.17, tail=0.0)[:n]
    return S.fade(out, sr, 0.003, 0.09)


def jingle_tier_4():
    """Excited: fast run up to a bright chord with a snare-ish roll (D major)."""
    sr, dur = SR_M, 1.75
    n = int(round(dur * sr))
    out = np.zeros(n)
    run = ["d4", "e4", "f#4", "g4", "a4", "b4", "c#5", "d5"]
    for i, nm in enumerate(run):
        S.place(out, _pluck(S.note(nm), 0.4, sr, 0.36, 0.28, 0.14), int(0.055 * i * sr))
    for nm in ("d4", "f#4", "a4", "d5", "f#5"):
        S.place(out, _bell(S.note(nm), 1.15, sr, 1.3, 0.40) * 0.27, int(round(0.45 * sr)))
    roll = S.bandpass(S.noise(0.45, sr), 1800, 7000, sr, 2)
    roll *= np.clip(np.linspace(0, 1, int(round(0.45 * sr))) ** 2, 0, 1) * \
        (0.55 + 0.45 * S.square(38.0, 0.45, sr))
    S.place(out, roll * 0.085, 0)
    out = S.reverb(out, sr, room=0.5, wet=0.2, tail=0.0)[:n]
    return S.fade(out, sr, 0.003, 0.10)


def jingle_tier_5():
    """Triumphant fanfare: dotted brass rhythm I-V-I, big finish (C major)."""
    sr, dur = SR_M, 1.95
    n = int(round(dur * sr))
    out = np.zeros(n)

    def brass(nm, t0, ln, gain):
        f0 = S.note(nm)
        m = int(round(ln * sr))
        u = np.linspace(0, 1, m)
        f = f0 * (1.0 + 0.012 * np.exp(-u * 18))      # little attack scoop
        v = 0.6 * S.saw(f, ln, sr) + 0.4 * S.pulse_bl(f, ln, sr, 0.35)
        cut = 1500 + 2600 * np.exp(-u * 3.0)
        v = S.sv_lowpass(v, cut, sr, q=1.1)
        v *= S.adsr(ln, sr, 0.012, 0.06, 0.8, 0.12, 1.4)
        v *= 1.0 + 0.05 * np.sin(2 * np.pi * 5.2 * np.arange(m) / sr)
        S.place(out, v * gain, int(t0 * sr))

    # rhythm: da-da-DAAA  (G G C)
    for nm in ("g3", "g4"):
        brass(nm, 0.00, 0.16, 0.30)
        brass(nm, 0.17, 0.11, 0.30)
    for nm in ("c4", "e4", "g4", "c5"):
        brass(nm, 0.30, 1.35, 0.26)
    for nm in ("c3", "c4"):
        brass(nm, 0.30, 1.35, 0.22)
    # cymbal-ish swell
    cym = S.bandpass(S.noise(0.7, sr), 3500, 9000, sr, 2) * S.perc_env(0.7, sr, 0.004, 0.16)
    S.place(out, cym * 0.07, int(round(0.30 * sr)))
    for nm in ("c5", "e5", "g5", "c6"):
        S.place(out, _bell(S.note(nm), 1.2, sr, 1.4, 0.40) * 0.16, int(0.33 * sr))
    out = S.soft_clip(out * 1.15, 1.3)
    out = S.reverb(out, sr, room=0.55, wet=0.2, tail=0.0)[:n]
    return S.fade(out, sr, 0.003, 0.12)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main():
    S.reseed()
    os.makedirs(OUT, exist_ok=True)
    written = []

    pops = target_pops()
    for i, y in enumerate(pops, 1):
        p = S.write_wav(os.path.join(OUT, f"sfx_target_pop_{i}.wav"), y, SR_T)
        written.append(p)

    hits = [hit_bonk(), hit_boing(), hit_squeak(), hit_splat(), hit_bwoop()]
    for i, y in enumerate(hits, 1):
        y = S.finish(y, SR_T, -3.0, L_HIT, fade_out=0.015)
        written.append(S.write_wav(os.path.join(OUT, f"sfx_hit_{i}.wav"), y, SR_T))

    simple = [
        ("sfx_miss", sfx_miss(), SR_T, L_SOFT, 0.03),
        ("sfx_ui_click", sfx_ui_click(), SR_T, -26.0, 0.005),
        ("sfx_false_start", sfx_false_start(), SR_M, L_SOFT + 2, 0.05),
        ("sfx_series_finish", sfx_series_finish(), SR_M, L_JINGLE, 0.08),
        ("sfx_new_record", sfx_new_record(), SR_M, L_JINGLE, 0.12),
        ("jingle_tier_1", jingle_tier_1(), SR_M, L_JINGLE - 1, 0.09),
        ("jingle_tier_2", jingle_tier_2(), SR_M, L_JINGLE, 0.08),
        ("jingle_tier_3", jingle_tier_3(), SR_M, L_JINGLE, 0.09),
        ("jingle_tier_4", jingle_tier_4(), SR_M, L_JINGLE, 0.10),
        ("jingle_tier_5", jingle_tier_5(), SR_M, L_JINGLE + 1, 0.12),
    ]
    for name, y, sr, lvl, fo in simple:
        y = S.finish(y, sr, -3.0, lvl, fade_out=fo)
        written.append(S.write_wav(os.path.join(OUT, f"{name}.wav"), y, sr))

    total = 0
    for p in written:
        sz = os.path.getsize(p)
        total += sz
        print(f"{os.path.basename(p):26s} {sz/1024:8.1f} KB")
    print(f"{'TOTAL SFX':26s} {total/1024:8.1f} KB")


if __name__ == "__main__":
    main()
