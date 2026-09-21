"""Seamless chiptune loops for Reaction Speed.

    python tool/audio/music.py

Writes music_menu.wav and music_game.wav (mono 16-bit PCM, 22.05 kHz) into
app/src/main/res/raw/.

Both loops are written into a buffer whose length is exactly
`bars * 4 * 60 / bpm * sr` samples and every note is added with wrap-around
indexing, so a note or reverb tail that runs past the end folds back onto the
start of the loop. That makes the loop point click-free by construction
instead of by fading, which would be audible.

MENU  - C major, 100 BPM, 12 bars = 28.8 s, progression | C | Am | F | G7 | x3
GAME  - A minor, 120 BPM, 16 bars = 32.0 s, progression | Am | F | C | G  | x4
        Deliberately sparse: the lead has a >=25 ms attack and there are no
        short bright accents, so nothing in the music can be mistaken for the
        target-appears cue (which has a <5 ms attack).
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth as S

OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "app", "src", "main", "res", "raw"))

SR = S.SR_TONAL


# --------------------------------------------------------------------------
# instrument voices
# --------------------------------------------------------------------------

def lead_pulse(f0, dur, sr, gain=1.0, duty=0.3, attack=0.006, vib=0.0):
    n = int(round(dur * sr))
    f = np.full(n, f0, dtype=float)
    if vib:
        t = np.arange(n) / sr
        f = f * (1.0 + vib * np.sin(2 * np.pi * 5.5 * t) * np.clip((t - 0.12) * 5, 0, 1))
    y = S.pulse_bl(f, dur, sr, duty)
    y *= S.adsr(dur, sr, attack, 0.08, 0.62, min(0.22, dur * 0.45), 1.4)
    y = S.lowpass(y, 5000, sr, 2)
    return y * gain


def lead_soft(f0, dur, sr, gain=1.0, attack=0.030):
    """Rounded lead for the game loop: slow attack, no click, no bite."""
    n = int(round(dur * sr))
    y = 0.65 * S.tri(f0, dur, sr) + 0.35 * S.pulse_bl(f0, dur, sr, 0.45)
    y *= S.adsr(dur, sr, attack, 0.10, 0.60, min(0.30, dur * 0.5), 1.6)
    y = S.lowpass(y, 3200, sr, 2)
    return y * gain


def bass(f0, dur, sr, gain=1.0):
    y = 0.75 * S.tri(f0, dur, sr) + 0.25 * S.sine(f0 * 0.5, dur, sr)
    y *= S.adsr(dur, sr, 0.008, 0.06, 0.75, min(0.10, dur * 0.4), 1.3)
    return S.lowpass(y, 900, sr, 2) * gain


def pad_chord(freqs, dur, sr, gain=1.0):
    y = np.zeros(int(round(dur * sr)))
    for f in freqs:
        y += S.pulse_bl(f, dur, sr, 0.5) / len(freqs)
    y *= S.adsr(dur, sr, 0.10, 0.20, 0.55, dur * 0.45, 1.4)
    return S.lowpass(y, 2400, sr, 2) * gain


def kick(dur, sr, gain=1.0):
    f = S.sweep(120.0, 45.0, dur, sr, "exp")
    y = S.sine(f, dur, sr) * S.perc_env(dur, sr, 0.002, 0.055, 1.2)
    return S.soft_clip(y * 1.2, 1.2) * gain


def hat(dur, sr, gain=1.0, open_=False):
    y = S.bandpass(S.noise(dur, sr), 6000, 10000, sr, 2)
    y *= S.perc_env(dur, sr, 0.0008, 0.055 if open_ else 0.014)
    return y * gain


def snare_soft(dur, sr, gain=1.0):
    y = S.bandpass(S.noise(dur, sr), 1400, 5200, sr, 2) * S.perc_env(dur, sr, 0.002, 0.055)
    y += 0.4 * S.sine(S.sweep(330.0, 190.0, dur, sr, "exp"), dur, sr) * \
        S.perc_env(dur, sr, 0.002, 0.04)
    return y * gain


def wood(dur, sr, gain=1.0):
    """Soft rim/woodblock - used in the game loop instead of a snare."""
    y = S.fm(900.0, 1.41, S.perc_env(dur, sr, 0.001, 0.012) * 4.0, dur, sr)
    y *= S.perc_env(dur, sr, 0.003, 0.025)
    return S.lowpass(y, 4000, sr, 2) * gain


# --------------------------------------------------------------------------
# sequencing helpers
# --------------------------------------------------------------------------

class Loop:
    def __init__(self, bpm: float, bars: int, sr: int = SR, beats_per_bar: int = 4):
        self.bpm = bpm
        self.sr = sr
        self.beats_per_bar = beats_per_bar
        self.beat = 60.0 / bpm
        self.n = int(round(bars * beats_per_bar * self.beat * sr))
        self.tracks: dict[str, np.ndarray] = {}

    def track(self, name: str) -> np.ndarray:
        if name not in self.tracks:
            self.tracks[name] = np.zeros(self.n)
        return self.tracks[name]

    def at(self, bar: int, beat: float) -> int:
        """Sample index of bar (0-based) + beat (0-based, may be fractional)."""
        return int(round(((bar * self.beats_per_bar) + beat) * self.beat * self.sr))

    def put(self, name: str, x: np.ndarray, bar: int, beat: float) -> None:
        S.place(self.track(name), x, self.at(bar, beat) % self.n, wrap=True)

    def mixdown(self, gains: dict[str, float]) -> np.ndarray:
        out = np.zeros(self.n)
        for name, t in self.tracks.items():
            out += gains.get(name, 1.0) * t
        return out


CHORDS = {
    "C":  ["c4", "e4", "g4"],
    "Am": ["a3", "c4", "e4"],
    "F":  ["f3", "a3", "c4"],
    "G":  ["g3", "b3", "d4"],
    "G7": ["g3", "b3", "d4", "f4"],
    "Em": ["e3", "g3", "b3"],
    "Dm": ["d3", "f3", "a3"],
}
ROOTS = {"C": "c2", "Am": "a1", "F": "f1", "G": "g1", "G7": "g1", "Em": "e1", "Dm": "d2"}


# --------------------------------------------------------------------------
# MENU loop - C major, 100 BPM, 12 bars
# --------------------------------------------------------------------------

def build_menu() -> np.ndarray:
    L = Loop(bpm=100.0, bars=12, sr=SR)
    prog = ["C", "Am", "F", "G7"] * 3
    b = L.beat

    # --- melody: an 8-note motif, transposed / varied on each pass ------
    # (beat-in-bar, scale-degree index into the bar's chord tones + passing)
    motifs = [
        # pass 1 - simple, sings the chord
        [(0.0, 0, 1.0), (1.0, 1, 0.5), (1.5, 2, 1.0), (2.5, 1, 1.5)],
        # pass 2 - syncopated answer
        [(0.0, 2, 0.5), (0.5, 1, 0.5), (1.0, 0, 1.0), (2.0, 2, 0.75), (2.75, 3, 1.25)],
        # pass 3 - up an octave, busier
        [(0.0, 3, 0.5), (0.5, 4, 0.5), (1.0, 3, 0.5), (1.5, 2, 1.0),
         (2.5, 3, 0.5), (3.0, 4, 1.0)],
    ]
    scale = ["c4", "d4", "e4", "f4", "g4", "a4", "b4", "c5", "d5", "e5", "f5", "g5"]

    for bar, ch in enumerate(prog):
        p = bar // 4
        tones = CHORDS[ch]
        for beat, deg, ln in motifs[p]:
            # degree 0..2 = chord tone, 3..4 = the two scale notes above the top
            if deg < len(tones):
                f = S.note(tones[deg])
            else:
                top = scale.index(tones[-1]) if tones[-1] in scale else 4
                f = S.note(scale[min(len(scale) - 1, top + (deg - len(tones) + 1) * 2)])
            if p == 2:
                f *= 2.0
            dur = ln * b * 0.95
            L.put("lead", lead_pulse(f, dur, SR, 0.34, 0.3 if p < 2 else 0.22,
                                     attack=0.008, vib=0.008 if ln >= 1.0 else 0.0),
                  bar, beat)

    # --- bass: root on 1, fifth on 3, little walk into the next bar -----
    for bar, ch in enumerate(prog):
        root = S.note(ROOTS[ch])
        L.put("bass", bass(root, 0.9 * b, SR, 0.44), bar, 0.0)
        L.put("bass", bass(root * 1.5, 0.45 * b, SR, 0.30), bar, 1.5)
        L.put("bass", bass(root, 0.9 * b, SR, 0.38), bar, 2.0)
        if bar % 4 == 3:
            nxt = S.note(ROOTS[prog[(bar + 1) % len(prog)]])
            L.put("bass", bass(nxt * 0.9438, 0.4 * b, SR, 0.30), bar, 3.5)
        else:
            L.put("bass", bass(root * 1.5, 0.4 * b, SR, 0.26), bar, 3.5)

    # --- chord stabs on the off-beats (the "ska" lift) ------------------
    for bar, ch in enumerate(prog):
        freqs = [S.note(x) for x in CHORDS[ch]]
        for beat in (1.5, 3.5) if bar // 4 == 0 else (0.5, 1.5, 2.5, 3.5):
            L.put("pad", pad_chord(freqs, 0.30 * b, SR, 0.15), bar, beat)

    # --- percussion: builds over the three passes ----------------------
    for bar in range(12):
        p = bar // 4
        L.put("drum", kick(0.30, SR, 0.55), bar, 0.0)
        L.put("drum", kick(0.30, SR, 0.40), bar, 2.5)
        if p >= 1:
            L.put("drum", snare_soft(0.22, SR, 0.22), bar, 1.0)
            L.put("drum", snare_soft(0.22, SR, 0.22), bar, 3.0)
        steps = 2 if p == 0 else 4
        for i in range(steps):
            L.put("drum", hat(0.10, SR, 0.13, open_=(i == steps - 1 and p == 2)),
                  bar, i * (4.0 / steps) + (0.5 if p else 0.0))

    out = L.mixdown({"lead": 1.0, "bass": 1.0, "pad": 1.0, "drum": 1.0})
    out = _loop_reverb(out, SR, room=0.4, wet=0.14)
    out = S.soft_clip(out * 1.15, 1.25)
    return S.normalize(S.dc_block(out, SR), -3.5)


# --------------------------------------------------------------------------
# GAME loop - A minor, 120 BPM, 16 bars
# --------------------------------------------------------------------------

def build_game() -> np.ndarray:
    L = Loop(bpm=120.0, bars=16, sr=SR)
    prog = ["Am", "F", "C", "G"] * 4
    b = L.beat

    # sparse two-note-per-bar motif; slow attacks only
    motif_by_pass = [
        [(0.0, 0, 1.5), (2.0, 1, 1.5)],
        [(0.0, 1, 1.0), (1.5, 0, 2.0)],
        [(0.0, 2, 1.5), (2.5, 1, 1.0)],
        [(0.0, 1, 1.0), (1.0, 2, 1.0), (2.0, 0, 1.75)],
    ]
    for bar, ch in enumerate(prog):
        p = bar // 4
        tones = CHORDS[ch]
        for beat, deg, ln in motif_by_pass[p]:
            f = S.note(tones[deg % len(tones)]) * (2.0 if p == 3 else 1.0)
            L.put("lead", lead_soft(f, ln * b * 0.98, SR, 0.30 if p < 3 else 0.24,
                                    attack=0.030), bar, beat)

    # pulsing eighth-note bass - the "tension" element, steady, never startling
    for bar, ch in enumerate(prog):
        root = S.note(ROOTS[ch]) * 2.0
        p = bar // 4
        pattern = [0.0, 1.0, 2.0, 3.0] if p == 0 else [0.0, 0.75, 1.5, 2.0, 2.75, 3.5]
        for i, beat in enumerate(pattern):
            f = root if i % 3 != 2 else root * 1.4983  # occasional fifth
            L.put("bass", bass(f, 0.38 * b, SR, 0.40), bar, beat)

    # Quiet sustained pad. It spans the full two bars rather than stopping
    # short, because without it the sparse arrangement leaves ~50 ms of literal
    # digital silence before every downbeat, which reads as a choppy loop.
    for bar, ch in enumerate(prog):
        if bar % 2 == 0:
            freqs = [S.note(x) * 0.5 for x in CHORDS[ch]]
            L.put("pad", pad_chord(freqs, 8.1 * b, SR, 0.085), bar, 0.0)

    # percussion: soft kick + closed hats + a wood tick, no snare accents
    for bar in range(16):
        p = bar // 4
        L.put("drum", kick(0.26, SR, 0.42), bar, 0.0)
        L.put("drum", kick(0.26, SR, 0.30), bar, 2.0)
        for i in range(8):
            if p == 0 and i % 2:
                continue
            L.put("drum", hat(0.07, SR, 0.075 if i % 2 else 0.095), bar, i * 0.5)
        if p >= 2:
            L.put("drum", wood(0.09, SR, 0.14), bar, 1.5)
            L.put("drum", wood(0.09, SR, 0.11), bar, 3.25)

    out = L.mixdown({"lead": 1.0, "bass": 1.0, "pad": 1.0, "drum": 1.0})
    out = _loop_reverb(out, SR, room=0.35, wet=0.10)
    out = S.soft_clip(out * 1.1, 1.2)
    return S.normalize(S.dc_block(out, SR), -4.5)   # a touch quieter than the menu


# --------------------------------------------------------------------------
# loop-safe reverb: render the tail and wrap it back onto the head
# --------------------------------------------------------------------------

def _loop_reverb(x: np.ndarray, sr: int, room: float, wet: float,
                 tail: float = 1.2) -> np.ndarray:
    n = x.size
    wetsig = S.reverb(x, sr, room=room, wet=1.0, tail=tail)
    head = wetsig[:n].copy()
    extra = wetsig[n:]
    if extra.size:
        m = min(extra.size, n)
        head[:m] += extra[:m]
    return (1 - wet) * x + wet * head


def main():
    S.reseed(7)
    os.makedirs(OUT, exist_ok=True)
    for name, fn in (("music_menu", build_menu), ("music_game", build_game)):
        y = fn()
        p = S.write_wav(os.path.join(OUT, f"{name}.wav"), y, SR)
        print(f"{name:12s} {y.size / SR:6.2f} s  {os.path.getsize(p)/1024:8.1f} KB  "
              f"peak {S.peak_dbfs(y):6.2f} dBFS  rms {S.rms_dbfs(y):6.2f} dBFS  "
              f"wrap |x[0]-x[-1]| = {abs(y[0]-y[-1]):.5f}")


if __name__ == "__main__":
    main()
