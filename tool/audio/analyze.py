"""QA for the generated audio - measurement instead of ears.

    python tool/audio/analyze.py            # all res/raw* assets
    python tool/audio/analyze.py music_game sfx_hit_1

Prints per file: sample rate, duration, size, peak dBFS, weighted RMS dBFS,
10->90 % attack time, DC offset, clipped-sample count, WAV format tag; and for
the loops the wrap-point discontinuity expressed as a percentile of the
interior sample-to-sample deltas (a wrap is clean when it sits inside that
distribution, not when it is literally zero).

Also runs hard assertions:
  * every file is PCM format tag 1
  * the three target pops share an attack < 5 ms and a first-50 ms RMS within
    0.5 dB of each other (reaction-time fairness)
  * no file is silent, DC-offset or clipped
  * loop wrap deltas are below the 99.9th interior percentile

Spectrogram PNGs are written to tool/audio/_preview/.
"""

from __future__ import annotations

import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth as S

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
PREVIEW = os.path.join(HERE, "_preview")
DIRS = ["raw", "raw-de", "raw-ru"]
LOOPS = {"music_menu", "music_game"}


# --------------------------------------------------------------------------
# WAV header check
# --------------------------------------------------------------------------

def wav_header(path: str) -> dict:
    with open(path, "rb") as f:
        riff, size, wave = struct.unpack("<4sI4s", f.read(12))
        info = {"riff": riff == b"RIFF", "wave": wave == b"WAVE"}
        while True:
            hdr = f.read(8)
            if len(hdr) < 8:
                break
            cid, clen = struct.unpack("<4sI", hdr)
            if cid == b"fmt ":
                fmt = struct.unpack("<HHIIHH", f.read(16))
                info.update(tag=fmt[0], channels=fmt[1], rate=fmt[2], bits=fmt[5])
                f.seek(clen - 16, 1)
            elif cid == b"data":
                info["data_bytes"] = clen
                f.seek(clen + (clen & 1), 1)
            else:
                f.seek(clen + (clen & 1), 1)
        return info


# --------------------------------------------------------------------------
# measurements
# --------------------------------------------------------------------------

def attack_time(x: np.ndarray, sr: int) -> float:
    """10 % -> 90 % of the first peak, on a 1 ms smoothed envelope."""
    w = max(1, int(0.001 * sr))
    env = np.convolve(np.abs(x), np.ones(w) / w, mode="same")
    if env.max() <= 0:
        return float("nan")
    peak_i = int(np.argmax(env))
    p = env[peak_i]
    seg = env[:peak_i + 1]
    i10 = np.flatnonzero(seg >= 0.1 * p)
    i90 = np.flatnonzero(seg >= 0.9 * p)
    if i10.size == 0 or i90.size == 0:
        return float("nan")
    return max(0.0, (i90[0] - i10[0]) / sr)


def wrap_report(x: np.ndarray) -> tuple[float, float, float]:
    """(wrap delta, 99.9th interior percentile, percentile rank of the wrap)."""
    d = np.abs(np.diff(x))
    wrap = abs(float(x[0] - x[-1]))
    p999 = float(np.percentile(d, 99.9))
    rank = float(100.0 * np.mean(d <= wrap))
    return wrap, p999, rank


def measure(path: str) -> dict:
    hdr = wav_header(path)
    sr, x = S.read_wav(path)
    raw = np.round(x * 32768.0)
    m = dict(
        name=os.path.basename(path), path=path, sr=sr, dur=x.size / sr,
        size=os.path.getsize(path),
        peak=S.peak_dbfs(x), rms=S.rms_dbfs(x),
        wrms=S.rms_dbfs(S.bandpass(x, 300, 6000, sr, 2)) if x.size > 64 else float("nan"),
        attack=attack_time(x, sr), dc=float(np.mean(x)),
        clipped=int(np.sum(np.abs(raw) >= 32767)),
        tag=hdr.get("tag"), bits=hdr.get("bits"), ch=hdr.get("channels"),
    )
    stem = os.path.splitext(m["name"])[0]
    if stem in LOOPS:
        m["wrap"], m["p999"], m["rank"] = wrap_report(x)
    m["_x"] = x
    return m


# --------------------------------------------------------------------------
# spectrograms
# --------------------------------------------------------------------------

def spectrogram_png(x: np.ndarray, sr: int, out: str, title: str) -> None:
    from scipy import signal as sg
    nper = 1024 if x.size > 4096 else 256
    f, t, Sxx = sg.spectrogram(x, fs=sr, nperseg=nper, noverlap=nper * 3 // 4,
                               window="hann", scaling="spectrum")
    db = 10 * np.log10(np.maximum(Sxx, 1e-12))
    db = np.clip(db, db.max() - 80.0, db.max())
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(9, 3.4), dpi=110)
        ax.pcolormesh(t, f, db, shading="auto", cmap="magma")
        ax.set_ylim(0, min(sr / 2, 11000))
        ax.set_xlabel("s")
        ax.set_ylabel("Hz")
        ax.set_title(title, fontsize=9)
        fig.tight_layout()
        fig.savefig(out)
        plt.close(fig)
    except Exception:  # pragma: no cover - PIL fallback
        from PIL import Image
        img = (255 * (db - db.min()) / max(1e-9, db.max() - db.min())).astype(np.uint8)
        img = np.flipud(img)
        Image.fromarray(img).resize((900, 340)).save(out)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

HDR = (f"{'file':26s} {'dir':7s} {'sr':>6s} {'dur':>6s} {'KB':>7s} {'peak':>7s} "
       f"{'rms':>7s} {'wRMS':>7s} {'atk ms':>7s} {'DC':>9s} {'clip':>5s} {'fmt':>4s}")


def main(argv):
    os.makedirs(PREVIEW, exist_ok=True)
    wanted = set(argv)
    rows = []
    for d in DIRS:
        dd = os.path.join(RES, d)
        if not os.path.isdir(dd):
            continue
        for fn in sorted(os.listdir(dd)):
            if not fn.lower().endswith(".wav"):
                continue
            stem = os.path.splitext(fn)[0]
            if wanted and stem not in wanted:
                continue
            m = measure(os.path.join(dd, fn))
            m["dir"] = d
            rows.append(m)

    print(HDR)
    print("-" * len(HDR))
    total = 0
    for m in rows:
        total += m["size"]
        wrap = ""
        if "wrap" in m:
            wrap = (f"  wrap={m['wrap']:.5f} p99.9={m['p999']:.5f} "
                    f"rank={m['rank']:.2f}%")
        print(f"{m['name']:26s} {m['dir']:7s} {m['sr']:6d} {m['dur']:6.2f} "
              f"{m['size']/1024:7.1f} {m['peak']:7.2f} {m['rms']:7.2f} "
              f"{m['wrms']:7.2f} {m['attack']*1000:7.2f} {m['dc']:9.5f} "
              f"{m['clipped']:5d} {m['tag']:4d}{wrap}")
    print("-" * len(HDR))
    by_dir = {}
    for m in rows:
        by_dir[m["dir"]] = by_dir.get(m["dir"], 0) + m["size"]
    for d, s in by_dir.items():
        print(f"  {d:10s} {s/1024:9.1f} KB")
    print(f"  {'TOTAL':10s} {total/1024:9.1f} KB  ({total/1024/1024:.2f} MB)")

    # ---------------- assertions ----------------
    problems = []
    for m in rows:
        if m["tag"] != 1:
            problems.append(f"{m['name']}: WAV format tag {m['tag']} (expected 1 = PCM)")
        if m["bits"] != 16:
            problems.append(f"{m['name']}: {m['bits']} bits (expected 16)")
        if m["peak"] < -20:
            problems.append(f"{m['name']}: nearly silent, peak {m['peak']:.1f} dBFS")
        if m["clipped"] > 0:
            problems.append(f"{m['name']}: {m['clipped']} clipped samples")
        if abs(m["dc"]) > 0.005:
            problems.append(f"{m['name']}: DC offset {m['dc']:.4f}")
        if "wrap" in m and m["wrap"] > m["p999"]:
            problems.append(f"{m['name']}: loop wrap {m['wrap']:.5f} exceeds "
                            f"interior p99.9 {m['p999']:.5f}")

    pops = [m for m in rows if m["name"].startswith("sfx_target_pop_")]
    if len(pops) == 3:
        head_rms = []
        for m in pops:
            n = int(0.05 * m["sr"])
            head_rms.append(S.rms_dbfs(m["_x"][:n]))
            if m["attack"] >= 0.005:
                problems.append(f"{m['name']}: attack {m['attack']*1000:.2f} ms >= 5 ms")
        spread = max(head_rms) - min(head_rms)
        onset_same = all(np.array_equal(
            np.round(pops[0]["_x"][:int(0.030 * pops[0]["sr"])] * 32768),
            np.round(m["_x"][:int(0.030 * m["sr"])] * 32768)) for m in pops)
        print(f"\npop fairness: first-50ms RMS spread {spread:.3f} dB, "
              f"first-30ms samples identical: {onset_same}")
        if spread > 0.5:
            problems.append(f"target pops: first-50 ms RMS spread {spread:.2f} dB > 0.5")
        if not onset_same:
            problems.append("target pops: onset windows are not sample-identical")

    # ---------------- suggested playback gains ----------------
    # Peaks are capped at -3 dBFS, so files with a high crest factor (the GRUFF
    # lines, the low thud) still end up perceptibly quieter than the rest. The
    # residual difference has to be made up at playback, and these numbers are
    # measured rather than guessed: gain = 10^((ref - wRMS)/20), clamped to 1.
    GROUPS = [
        ("SFX  (SoundPool)", -18.5, lambda n: n.startswith(("sfx_", "jingle_"))),
        ("VOX  (SoundPool)", -19.0, lambda n: n.startswith("vox_")),
        ("VOICE (SoundPool)", -17.0, lambda n: n.startswith("voice_")),
    ]
    print("\nsuggested playback gains (relative to the group reference)")
    for label, ref, pred in GROUPS:
        print(f"  {label}  reference wRMS {ref:.1f} dBFS")
        seen = set()
        for m in rows:
            stem = os.path.splitext(m["name"])[0]
            if not pred(stem) or stem in seen or not np.isfinite(m["wrms"]):
                continue
            seen.add(stem)
            g = min(1.0, 10 ** ((ref - m["wrms"]) / 20.0))
            flag = "  <- needs a boost" if g > 0.999 and m["wrms"] < ref - 1.0 else ""
            print(f"      {stem:24s} wRMS {m['wrms']:7.2f}  gain {g:5.2f}{flag}")

    # ---------------- spectrograms ----------------
    for m in rows:
        out = os.path.join(PREVIEW, f"{m['dir']}__{os.path.splitext(m['name'])[0]}.png")
        spectrogram_png(m["_x"], m["sr"], out,
                        f"{m['dir']}/{m['name']}  {m['dur']:.2f}s  peak {m['peak']:.1f} dBFS")
    print(f"\n{len(rows)} spectrograms -> {PREVIEW}")

    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  ! " + p)
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
