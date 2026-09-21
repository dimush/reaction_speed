"""Shared DSP helpers for the Reaction Speed audio pipeline.

Everything here is pure numpy/scipy, deterministic (seeded RNG) and offline.
Audio is carried around as float64 mono numpy arrays in [-1, 1] unless noted.

Run nothing directly here; see sfx.py / music.py / voices_post.py.
"""

from __future__ import annotations

import os
import numpy as np
from scipy import signal
from scipy.io import wavfile

# --------------------------------------------------------------------------
# constants
# --------------------------------------------------------------------------

SR_SFX = 44100      # short transient SFX
SR_TONAL = 22050    # jingles / stingers / music / voices
SEED = 20260921

PEAK_TARGET_DBFS = -3.0

_rng = np.random.default_rng(SEED)


def rng() -> np.random.Generator:
    """The shared seeded generator (kept global so regeneration is bit-identical)."""
    return _rng


def reseed(extra: int = 0) -> None:
    """Reset the shared RNG; call at the top of every generator script."""
    global _rng
    _rng = np.random.default_rng(SEED + extra)


# --------------------------------------------------------------------------
# basic time / level utilities
# --------------------------------------------------------------------------

def t_axis(dur: float, sr: int) -> np.ndarray:
    return np.arange(int(round(dur * sr)), dtype=np.float64) / sr


def silence(dur: float, sr: int) -> np.ndarray:
    return np.zeros(int(round(dur * sr)), dtype=np.float64)


def db2lin(db: float) -> float:
    return float(10.0 ** (db / 20.0))


def lin2db(x: float) -> float:
    return -200.0 if x <= 1e-10 else float(20.0 * np.log10(x))


def peak_dbfs(x: np.ndarray) -> float:
    return lin2db(float(np.max(np.abs(x))) if x.size else 0.0)


def rms_dbfs(x: np.ndarray) -> float:
    return lin2db(float(np.sqrt(np.mean(x ** 2))) if x.size else 0.0)


def pad_to(x: np.ndarray, n: int) -> np.ndarray:
    """Zero-pad or truncate to exactly n samples."""
    if x.size == n:
        return x
    if x.size > n:
        return x[:n].copy()
    out = np.zeros(n, dtype=np.float64)
    out[:x.size] = x
    return out


def mix(*parts: np.ndarray) -> np.ndarray:
    """Sum arrays of differing lengths, zero-padding to the longest."""
    parts = [p for p in parts if p is not None and p.size]
    if not parts:
        return np.zeros(0)
    n = max(p.size for p in parts)
    out = np.zeros(n, dtype=np.float64)
    for p in parts:
        out[:p.size] += p
    return out


def place(dst: np.ndarray, src: np.ndarray, at: int, wrap: bool = False) -> None:
    """Add src into dst at sample index `at`, in place.

    With wrap=True indices are taken modulo len(dst) so note releases fold back
    to the start of a loop (this is what makes the loop point click-free).
    """
    n = dst.size
    if wrap:
        idx = (np.arange(src.size) + at) % n
        np.add.at(dst, idx, src)
    else:
        a = max(0, at)
        b = min(n, at + src.size)
        if b > a:
            dst[a:b] += src[a - at: b - at]


# --------------------------------------------------------------------------
# envelopes
# --------------------------------------------------------------------------

def adsr(dur: float, sr: int, a: float = 0.005, d: float = 0.05,
         s: float = 0.6, r: float = 0.1, curve: float = 2.0) -> np.ndarray:
    """Classic ADSR. `dur` is the total length including the release."""
    n = int(round(dur * sr))
    na = max(1, int(round(a * sr)))
    nd = max(1, int(round(d * sr)))
    nr = max(1, int(round(r * sr)))
    ns = max(0, n - na - nd - nr)
    if ns == 0:  # squeeze when the note is shorter than a+d+r
        scale = n / float(na + nd + nr)
        na, nd, nr = max(1, int(na * scale)), max(1, int(nd * scale)), max(1, int(nr * scale))
        ns = max(0, n - na - nd - nr)
    env = np.concatenate([
        np.linspace(0.0, 1.0, na) ** (1.0 / curve),
        s + (1.0 - s) * (np.linspace(1.0, 0.0, nd) ** curve),
        np.full(ns, s),
        s * (np.linspace(1.0, 0.0, nr) ** curve),
    ])
    return pad_to(env, n)


def perc_env(dur: float, sr: int, attack: float = 0.002, decay: float = 0.25,
             curve: float = 1.0) -> np.ndarray:
    """Percussive: fast attack then exponential-ish decay to zero at `dur`."""
    n = int(round(dur * sr))
    na = max(1, int(round(attack * sr)))
    na = min(na, max(1, n - 1))
    t = np.arange(n - na, dtype=np.float64) / sr
    body = np.exp(-t / max(1e-4, decay))
    body *= np.linspace(1.0, 0.0, body.size) ** curve  # force a true zero at the end
    return np.concatenate([np.linspace(0.0, 1.0, na), body])[:n]


def fade(x: np.ndarray, sr: int, fin: float = 0.002, fout: float = 0.01) -> np.ndarray:
    """Short raised-cosine fades so nothing starts or ends on a step."""
    y = x.copy()
    ni = min(int(round(fin * sr)), y.size // 2)
    no = min(int(round(fout * sr)), y.size // 2)
    if ni > 0:
        y[:ni] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, ni))
    if no > 0:
        y[-no:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, no))
    return y


# --------------------------------------------------------------------------
# oscillators
# --------------------------------------------------------------------------

def phase_of(freq, n: int, sr: int, phase0: float = 0.0) -> np.ndarray:
    """Cumulative phase (radians) for a constant or per-sample frequency."""
    f = np.full(n, float(freq)) if np.isscalar(freq) else pad_to(np.asarray(freq, float), n)
    return phase0 + 2.0 * np.pi * np.cumsum(f) / sr


def sine(freq, dur: float, sr: int, phase0: float = 0.0) -> np.ndarray:
    n = int(round(dur * sr))
    return np.sin(phase_of(freq, n, sr, phase0))


def tri(freq, dur: float, sr: int) -> np.ndarray:
    n = int(round(dur * sr))
    p = phase_of(freq, n, sr) / (2 * np.pi)
    return 2.0 * np.abs(2.0 * (p - np.floor(p + 0.5))) - 1.0


def saw(freq, dur: float, sr: int) -> np.ndarray:
    """Mildly band-limited saw (additive, harmonics capped below Nyquist)."""
    n = int(round(dur * sr))
    p = phase_of(freq, n, sr)
    f0 = float(np.mean(freq)) if not np.isscalar(freq) else float(freq)
    k_max = max(1, min(40, int(0.45 * sr / max(20.0, f0))))
    out = np.zeros(n)
    for k in range(1, k_max + 1):
        out += np.sin(k * p) / k
    return out * (2.0 / np.pi)


def square(freq, dur: float, sr: int, duty: float = 0.5) -> np.ndarray:
    n = int(round(dur * sr))
    p = phase_of(freq, n, sr) / (2 * np.pi)
    return np.where((p % 1.0) < duty, 1.0, -1.0)


def pulse_bl(freq, dur: float, sr: int, duty: float = 0.25) -> np.ndarray:
    """Band-limited pulse as the difference of two saws (chiptune staple)."""
    n = int(round(dur * sr))
    p = phase_of(freq, n, sr)
    f0 = float(np.mean(freq)) if not np.isscalar(freq) else float(freq)
    k_max = max(1, min(32, int(0.45 * sr / max(20.0, f0))))
    out = np.zeros(n)
    for k in range(1, k_max + 1):
        out += (np.sin(k * p) - np.sin(k * (p + 2 * np.pi * duty))) / k
    return out * (1.0 / np.pi)


def noise(dur: float, sr: int) -> np.ndarray:
    return _rng.standard_normal(int(round(dur * sr)))


def fm(carrier, ratio: float, index, dur: float, sr: int) -> np.ndarray:
    """Simple 2-operator FM. `index` may be scalar or an envelope."""
    n = int(round(dur * sr))
    c = np.full(n, float(carrier)) if np.isscalar(carrier) else pad_to(np.asarray(carrier, float), n)
    idx = np.full(n, float(index)) if np.isscalar(index) else pad_to(np.asarray(index, float), n)
    mod = np.sin(phase_of(c * ratio, n, sr))
    return np.sin(phase_of(c, n, sr) + idx * mod)


def sweep(f0: float, f1: float, dur: float, sr: int, curve: str = "exp") -> np.ndarray:
    """Per-sample frequency array for a pitch sweep."""
    n = int(round(dur * sr))
    u = np.linspace(0.0, 1.0, max(1, n))
    if curve == "exp":
        return f0 * (max(1e-3, f1) / max(1e-3, f0)) ** u
    if curve == "log":
        return f0 + (f1 - f0) * np.sqrt(u)
    return f0 + (f1 - f0) * u


# --------------------------------------------------------------------------
# filters
# --------------------------------------------------------------------------

def _sos(kind: str, cutoff, sr: int, order: int = 2, q: float | None = None):
    ny = 0.5 * sr
    if kind in ("bandpass", "bandstop"):
        lo, hi = cutoff
        wn = [max(1e-4, lo / ny), min(0.999, hi / ny)]
    else:
        wn = min(0.999, max(1e-4, cutoff / ny))
    return signal.butter(order, wn, btype=kind, output="sos")


def lowpass(x, cutoff, sr, order=2):
    return signal.sosfilt(_sos("lowpass", cutoff, sr, order), x)


def highpass(x, cutoff, sr, order=2):
    return signal.sosfilt(_sos("highpass", cutoff, sr, order), x)


def bandpass(x, lo, hi, sr, order=2):
    return signal.sosfilt(_sos("bandpass", (lo, hi), sr, order), x)


def dc_block(x, sr, cutoff=30.0):
    return highpass(x, cutoff, sr, order=2)


def sv_lowpass(x: np.ndarray, cutoff: np.ndarray, sr: int, q: float = 1.0) -> np.ndarray:
    """State-variable low-pass with a per-sample cutoff (for filter sweeps)."""
    n = x.size
    cut = np.clip(pad_to(np.asarray(cutoff, float), n), 20.0, 0.45 * sr)
    f = 2.0 * np.sin(np.pi * cut / sr)
    damp = 1.0 / max(0.5, q)
    low = band = 0.0
    out = np.empty(n)
    for i in range(n):
        high = x[i] - low - damp * band
        band += f[i] * high
        low += f[i] * band
        out[i] = low
    return out


def peaking(x: np.ndarray, f0: float, gain_db: float, q: float, sr: int) -> np.ndarray:
    """RBJ peaking EQ."""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * f0 / sr
    alpha = np.sin(w0) / (2 * q)
    b = np.array([1 + alpha * A, -2 * np.cos(w0), 1 - alpha * A])
    a = np.array([1 + alpha / A, -2 * np.cos(w0), 1 - alpha / A])
    return signal.lfilter(b / a[0], a / a[0], x)


def formant_bank(x: np.ndarray, formants, sr: int) -> np.ndarray:
    """Sum of resonant band-passes. `formants` = [(freq, bw, gain), ...]."""
    out = np.zeros_like(x)
    for f0, bw, g in formants:
        f0 = float(np.clip(f0, 60.0, 0.44 * sr))
        bw = float(max(30.0, bw))
        lo, hi = max(30.0, f0 - bw / 2), min(0.45 * sr, f0 + bw / 2)
        if hi <= lo * 1.02:
            hi = lo * 1.05
        out += g * bandpass(x, lo, hi, sr, order=2)
    return out


# --------------------------------------------------------------------------
# time / space effects
# --------------------------------------------------------------------------

def delay(x: np.ndarray, sr: int, time: float = 0.09, feedback: float = 0.3,
          wet: float = 0.3, tail: float = 0.0) -> np.ndarray:
    d = max(1, int(round(time * sr)))
    n = x.size + int(round(tail * sr))
    buf = pad_to(x, n).copy()
    for i in range(d, n):
        buf[i] += feedback * buf[i - d]
    return (1 - wet) * pad_to(x, n) + wet * buf


def reverb(x: np.ndarray, sr: int, room: float = 0.5, wet: float = 0.2,
           tail: float = 0.4) -> np.ndarray:
    """Cheap Schroeder reverb: 4 combs + 2 all-passes."""
    n = x.size + int(round(tail * sr))
    dry = pad_to(x, n)
    comb_ms = np.array([29.7, 37.1, 41.1, 43.7]) * (0.7 + 0.6 * room)
    acc = np.zeros(n)
    for ms in comb_ms:
        d = max(1, int(round(ms * 1e-3 * sr)))
        g = 0.78 * room + 0.12
        y = dry.copy()
        for i in range(d, n):
            y[i] += g * y[i - d]
        acc += y / len(comb_ms)
    for ms, g in ((5.0, 0.7), (1.7, 0.7)):
        d = max(1, int(round(ms * 1e-3 * sr)))
        y = acc.copy()
        for i in range(d, n):
            y[i] += -g * acc[i] + g * y[i - d]
        acc = y
    acc = lowpass(acc, 6000, sr, 2)
    return (1 - wet) * dry + wet * acc


def chorus(x: np.ndarray, sr: int, depth_ms: float = 4.0, rate: float = 1.2,
           wet: float = 0.3) -> np.ndarray:
    n = x.size
    base = 0.012 * sr
    mod = base + depth_ms * 1e-3 * sr * np.sin(2 * np.pi * rate * np.arange(n) / sr)
    idx = np.arange(n) - mod
    idx = np.clip(idx, 0, n - 1)
    wetsig = np.interp(idx, np.arange(n), x)
    return (1 - wet) * x + wet * wetsig


def vibrato(x: np.ndarray, sr: int, rate: float = 6.0, depth_ms: float = 1.5) -> np.ndarray:
    n = x.size
    base = depth_ms * 1e-3 * sr
    mod = base * (1.0 + np.sin(2 * np.pi * rate * np.arange(n) / sr))
    idx = np.clip(np.arange(n) - mod, 0, n - 1)
    return np.interp(idx, np.arange(n), x)


# --------------------------------------------------------------------------
# saturation / dynamics
# --------------------------------------------------------------------------

def soft_clip(x: np.ndarray, drive: float = 1.5) -> np.ndarray:
    return np.tanh(drive * x) / np.tanh(drive)


def hard_ish(x: np.ndarray, drive: float = 3.0) -> np.ndarray:
    y = drive * x
    return np.sign(y) * (1.0 - np.exp(-np.abs(y)))


def compress(x: np.ndarray, sr: int, thresh_db: float = -18.0, ratio: float = 4.0,
             attack: float = 0.004, release: float = 0.09, makeup_db: float = 0.0) -> np.ndarray:
    """Simple feed-forward peak compressor with exponential envelope following."""
    env = np.abs(x)
    aa = np.exp(-1.0 / max(1.0, attack * sr))
    ar = np.exp(-1.0 / max(1.0, release * sr))
    e = np.empty_like(env)
    prev = 0.0
    for i in range(env.size):
        c = aa if env[i] > prev else ar
        prev = c * prev + (1 - c) * env[i]
        e[i] = prev
    e_db = 20 * np.log10(np.maximum(e, 1e-8))
    over = np.maximum(0.0, e_db - thresh_db)
    gain_db = -over * (1.0 - 1.0 / ratio) + makeup_db
    return x * (10 ** (gain_db / 20.0))


# --------------------------------------------------------------------------
# pitch / time manipulation
# --------------------------------------------------------------------------

def resample_ratio(x: np.ndarray, ratio: float) -> np.ndarray:
    """Playback-rate change: ratio>1 => higher pitch AND shorter (tape style)."""
    n_out = max(1, int(round(x.size / ratio)))
    src = np.linspace(0.0, x.size - 1.0, n_out)
    return np.interp(src, np.arange(x.size), x)


def semitones(n: float) -> float:
    return float(2.0 ** (n / 12.0))


def time_stretch(x: np.ndarray, sr: int, rate: float, win_ms: float = 40.0) -> np.ndarray:
    """WSOLA time stretch. rate>1 => longer output, pitch unchanged."""
    if abs(rate - 1.0) < 1e-3 or x.size < 4:
        return x.copy()
    win = int(round(win_ms * 1e-3 * sr))
    win = max(64, win - win % 2)
    hop_out = win // 2
    hop_in = max(1, int(round(hop_out / rate)))
    search = max(1, int(round(0.004 * sr)))
    w = np.hanning(win)
    n_out = int(x.size * rate) + win
    out = np.zeros(n_out)
    norm = np.zeros(n_out)
    pos_in = 0
    pos_out = 0
    prev_tail = np.zeros(hop_out)
    while pos_in + win + search < x.size and pos_out + win < n_out:
        lo = max(0, pos_in - search)
        hi = min(x.size - win, pos_in + search)
        best, best_score = pos_in, -1e18
        if hi > lo:
            for cand in range(lo, hi + 1, max(1, (hi - lo) // 16)):
                seg = x[cand:cand + hop_out]
                score = float(np.dot(seg, prev_tail))
                if score > best_score:
                    best_score, best = score, cand
        seg = x[best:best + win] * w
        out[pos_out:pos_out + win] += seg
        norm[pos_out:pos_out + win] += w
        prev_tail = x[best + hop_out: best + hop_out + hop_out]
        if prev_tail.size < hop_out:
            prev_tail = pad_to(prev_tail, hop_out)
        pos_in += hop_in
        pos_out += hop_out
    norm[norm < 1e-6] = 1.0
    out = out[:pos_out + win] / norm[:pos_out + win]
    return out


def pitch_shift(x: np.ndarray, sr: int, semis: float) -> np.ndarray:
    """Pitch shift keeping duration: resample then WSOLA back."""
    r = semitones(semis)
    y = resample_ratio(x, r)          # higher & shorter
    return time_stretch(y, sr, r)     # back to the original length


# --------------------------------------------------------------------------
# spectral envelope (formant) tools
# --------------------------------------------------------------------------

def lpc(x: np.ndarray, order: int = 18) -> np.ndarray:
    """Levinson-Durbin LPC coefficients (a[0] == 1)."""
    x = x - np.mean(x)
    r = np.correlate(x, x, mode="full")[x.size - 1: x.size + order]
    if r[0] <= 0:
        return np.concatenate([[1.0], np.zeros(order)])
    r = r / r[0]
    a = np.zeros(order + 1)
    a[0] = 1.0
    e = r[0]
    for i in range(1, order + 1):
        acc = r[i] + np.dot(a[1:i], r[i - 1:0:-1]) if i > 1 else r[1]
        k = -acc / max(1e-9, e)
        a_new = a.copy()
        for j in range(1, i):
            a_new[j] = a[j] + k * a[i - j]
        a_new[i] = k
        a = a_new
        e *= (1 - k * k)
        if e <= 1e-9:
            break
    return a


def cepstral_envelope(mag: np.ndarray, lifter: int = 40) -> np.ndarray:
    """Smooth spectral envelope of a magnitude spectrum via cepstral liftering."""
    log_mag = np.log(np.maximum(mag, 1e-9))
    cep = np.fft.irfft(log_mag, n=2 * (mag.size - 1))
    cep[lifter:-lifter] = 0.0
    return np.exp(np.fft.rfft(cep, n=2 * (mag.size - 1)).real)


def formant_warp(x: np.ndarray, sr: int, factor: float, lifter: int = 34,
                 n_fft: int = 1024) -> np.ndarray:
    """Shift the spectral envelope by `factor` while leaving the pitch alone.

    STFT -> cepstral envelope per frame -> resample the envelope along frequency
    -> re-apply as a gain mask. Because only a magnitude *ratio* is applied and
    the original phase is kept, this is far less metallic than re-synthesising.
    """
    if abs(factor - 1.0) < 1e-3 or x.size < n_fft:
        return x.copy()
    hop = n_fft // 4
    f, t, Z = signal.stft(x, fs=sr, nperseg=n_fft, noverlap=n_fft - hop, window="hann")
    mag = np.abs(Z)
    bins = np.arange(mag.shape[0])
    out = np.empty_like(Z)
    for i in range(mag.shape[1]):
        env = cepstral_envelope(mag[:, i], lifter=lifter)
        warped = np.interp(bins / factor, bins, env, left=env[0], right=env[-1])
        gain = warped / np.maximum(env, 1e-7)
        gain = np.clip(gain, 0.15, 6.0)
        out[:, i] = Z[:, i] * gain
    _, y = signal.istft(out, fs=sr, nperseg=n_fft, noverlap=n_fft - hop, window="hann")
    return pad_to(np.asarray(y, float), x.size)


def pitch_contour(x: np.ndarray, sr: int, semis_curve: np.ndarray,
                  keep_length: bool = True) -> np.ndarray:
    """Apply a time-varying pitch shift (e.g. a rising exclamation glide).

    `semis_curve` is resampled to the length of x. Implemented as a variable
    read-rate resample (which also warps time) followed by a WSOLA stretch back
    to the original length when keep_length is set.
    """
    n = x.size
    if n < 8:
        return x.copy()
    curve = np.interp(np.linspace(0, 1, n), np.linspace(0, 1, len(semis_curve)),
                      np.asarray(semis_curve, float))
    rate = 2.0 ** (curve / 12.0)
    pos = np.cumsum(rate)
    pos = pos - pos[0]
    n_out = max(8, int(np.floor(pos[-1])))
    src = np.interp(np.linspace(0.0, pos[-1], n_out), pos, np.arange(n))
    y = np.interp(src, np.arange(n), x)
    if keep_length and y.size != n:
        y = time_stretch(y, sr, n / max(1, y.size))
        y = pad_to(y, n)
    return y


def stretch_loudest(x: np.ndarray, sr: int, factor: float = 1.4,
                    win: float = 0.12) -> np.ndarray:
    """Comic timing: elongate the most energetic (stressed) vowel only."""
    if factor <= 1.001 or x.size < int(0.3 * sr):
        return x.copy()
    w = max(1, int(win * sr))
    env = np.convolve(np.abs(x), np.ones(w) / w, mode="same")
    c = int(np.argmax(env))
    a = max(0, c - w // 2)
    b = min(x.size, c + w // 2)
    mid = time_stretch(x[a:b], sr, factor)
    return np.concatenate([x[:a], mid, x[b:]])


def pitch_formant_shift(x: np.ndarray, sr: int, pitch_semis: float,
                        formant_semis: float | None = None) -> np.ndarray:
    """Independent pitch and formant shift.

    Plain resampling moves pitch and formants together by `pitch_semis`; the
    warp then corrects the envelope to land on `formant_semis`.
    """
    y = pitch_shift(x, sr, pitch_semis)
    if formant_semis is None or abs(formant_semis - pitch_semis) < 1e-3:
        return y
    return formant_warp(y, sr, semitones(formant_semis - pitch_semis))


def tape_shift(x: np.ndarray, sr: int, pitch_semis: float,
               formant_semis: float | None = None, restore: float = 0.0) -> np.ndarray:
    """Pitch + independent formant shift with as little time-stretching as possible.

    Straight resampling ("tape speed") moves pitch and formants together and
    changes the duration; `formant_warp` then places the formants wherever we
    want them. `restore` in 0..1 says how much of the duration change to undo
    with WSOLA: 0 keeps the tape timing (zero stretch artefacts), 1 restores the
    original length. Character voices use a small value, because a lumbering
    monster *should* be slower and a tiny one *should* be faster, and because a
    2x WSOLA stretch is exactly what makes pitch-shifted speech sound metallic.
    """
    r = semitones(pitch_semis)
    y = resample_ratio(x, r)
    if restore > 1e-3:
        y = time_stretch(y, sr, r ** restore)
    if formant_semis is not None and abs(formant_semis - pitch_semis) > 1e-3:
        y = formant_warp(y, sr, semitones(formant_semis - pitch_semis))
    return y


# --------------------------------------------------------------------------
# normalisation & I/O
# --------------------------------------------------------------------------

def trim_silence(x: np.ndarray, sr: int, thresh_db: float = -45.0,
                 pad_ms: float = 12.0) -> np.ndarray:
    if not x.size:
        return x
    thr = db2lin(thresh_db) * max(1e-9, np.max(np.abs(x)))
    win = max(1, int(0.005 * sr))
    env = np.convolve(np.abs(x), np.ones(win) / win, mode="same")
    idx = np.flatnonzero(env > thr)
    if idx.size == 0:
        return x
    pad = int(pad_ms * 1e-3 * sr)
    a = max(0, idx[0] - pad)
    b = min(x.size, idx[-1] + pad)
    return x[a:b].copy()


def normalize(x: np.ndarray, peak_db: float = PEAK_TARGET_DBFS) -> np.ndarray:
    p = float(np.max(np.abs(x))) if x.size else 0.0
    if p < 1e-9:
        return x
    return x * (db2lin(peak_db) / p)


def normalize_group(arrays, peak_db: float = PEAK_TARGET_DBFS):
    """Scale a set of signals by ONE shared gain (keeps relative levels intact).

    Used for the target-pop trio, where per-file normalisation would make the
    onsets differ in loudness and bias reaction times.
    """
    p = max((float(np.max(np.abs(a))) for a in arrays if a.size), default=0.0)
    if p < 1e-9:
        return [a.copy() for a in arrays]
    g = db2lin(peak_db) / p
    return [a * g for a in arrays]


def loudness_match(x: np.ndarray, sr: int, target_rms_db: float,
                   peak_ceiling_db: float = PEAK_TARGET_DBFS) -> np.ndarray:
    """Match perceived loudness (A-ish weighted RMS) then guard the peak.

    Keeps SFX from jumping around in volume even though their peak levels are
    all the same.
    """
    w = bandpass(x, 300.0, 6000.0, sr, order=2)
    r = float(np.sqrt(np.mean(w ** 2))) if w.size else 0.0
    if r < 1e-7:
        return x
    y = x * (db2lin(target_rms_db) / r)
    p = float(np.max(np.abs(y)))
    ceiling = db2lin(peak_ceiling_db)
    if p > ceiling:
        y = soft_clip(y * (1.0 / p) * 1.05, drive=1.2)
        y = y * (ceiling / max(1e-9, float(np.max(np.abs(y)))))
    return y


def finish(x: np.ndarray, sr: int, peak_db: float = PEAK_TARGET_DBFS,
           target_rms_db: float | None = None, fade_out: float = 0.008,
           hp: float = 30.0) -> np.ndarray:
    """DC-block, fade, loudness/peak normalise — the standard last step."""
    y = dc_block(x, sr, hp)
    y = fade(y, sr, fin=0.0005, fout=fade_out)
    if target_rms_db is not None:
        y = loudness_match(y, sr, target_rms_db, peak_db)
    else:
        y = normalize(y, peak_db)
    return y


def write_wav(path: str, x: np.ndarray, sr: int) -> str:
    """Write standard 16-bit PCM (format tag 1). Clips before casting."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    y = np.asarray(x, dtype=np.float64)
    if y.ndim == 2:  # (n, channels)
        y = np.clip(y, -1.0, 1.0)
        data = (y * 32767.0).astype(np.int16)
    else:
        y = np.clip(y, -1.0, 1.0)
        data = (y * 32767.0).astype(np.int16)
    wavfile.write(path, sr, data)
    return path


def read_wav(path: str) -> tuple[int, np.ndarray]:
    """Read a WAV as float mono in [-1, 1]."""
    sr, data = wavfile.read(path)
    x = np.asarray(data)
    if x.ndim == 2:
        x = x.mean(axis=1)
    if x.dtype == np.int16:
        x = x.astype(np.float64) / 32768.0
    elif x.dtype == np.int32:
        x = x.astype(np.float64) / 2147483648.0
    elif x.dtype == np.uint8:
        x = (x.astype(np.float64) - 128.0) / 128.0
    else:
        x = x.astype(np.float64)
    return sr, x


def resample_to(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out:
        return x.copy()
    n_out = int(round(x.size * sr_out / sr_in))
    return signal.resample_poly(x, sr_out, sr_in) if n_out > 8 else np.interp(
        np.linspace(0, x.size - 1, n_out), np.arange(x.size), x)


# --------------------------------------------------------------------------
# musical helpers
# --------------------------------------------------------------------------

NOTE_BASE = {"c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11}


def note(name: str) -> float:
    """'a4' -> 440.0, 'c#5', 'eb3' also work."""
    s = name.strip().lower()
    semi = NOTE_BASE[s[0]]
    i = 1
    while i < len(s) and s[i] in "#b":
        semi += 1 if s[i] == "#" else -1
        i += 1
    octave = int(s[i:])
    midi = 12 * (octave + 1) + semi
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def midi_hz(m: float) -> float:
    return 440.0 * 2.0 ** ((m - 69) / 12.0)
