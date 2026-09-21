# tool/audio — procedural sound design for Reaction Speed

Everything here is generated **locally and reproducibly**: no downloads, no
sample libraries, no pip installs. Requirements are only what the dev box
already has:

* Python 3.12 with **numpy 2.3** and **scipy 1.16** (matplotlib/PIL optional,
  used only for the QA spectrograms)
* Windows **System.Speech** offline TTS (PowerShell) for the raw voice takes

Output is **16-bit PCM WAV (format tag 1)**. There is no ogg/vorbis encoder on
this machine, and Android's `SoundPool` / `MediaPlayer` play PCM WAV natively.
The legacy `res/raw/*.ogg` files (`drip`, `metal`, `stone2`) are left untouched.

## Regenerate everything

```powershell
python tool\audio\sfx.py                                       # SFX + jingles
python tool\audio\music.py                                     # the two loops
powershell -ExecutionPolicy Bypass -File tool\audio\voices.ps1 # raw TTS takes
python tool\audio\voices_post.py                               # character voices + vox
python tool\audio\analyze.py                                   # QA table + spectrograms
```

Each step is independent. In particular `voices_post.py` can be re-run on its
own — it never touches SFX or music — which is the normal loop while tuning a
character or after new raw takes arrive.

Determinism: every generator calls `synth.reseed(...)`, so re-running produces
byte-identical files.

## Files

| file | what it is |
|---|---|
| `synth.py` | shared DSP library: envelopes, oscillators, FM, noise, filters, sweeps, reverb/delay/chorus/vibrato, soft clipping, compressor, WSOLA time-stretch, pitch shift, tape shift, LPC / cepstral formant warping, normalisation, WAV I/O, note names |
| `sfx.py` | target pops, hits, miss, false start, UI click, series finish, new record, `jingle_tier_1..5` |
| `music.py` | `music_menu` and `music_game` seamless loops |
| `voice_lines.json` | the line table: text per language, character, SAPI prosody hints |
| `voices.ps1` | renders raw SAPI takes into `_raw_tts/` (22050 Hz / 16-bit / mono) |
| `voices_post.py` | character DSP chains + the synthesised `vox_*` monster vocalisations |
| `analyze.py` | QA measurements, assertions and spectrogram PNGs into `_preview/` |

Intermediate directories `_raw_tts/` and `_preview/` are working files, not
shipped assets.

## Where the output goes

* `app/src/main/res/raw/` — all SFX, jingles, music, the `vox_*` set, and the
  **English** `voice_*` lines
* `app/src/main/res/raw-de/`, `app/src/main/res/raw-ru/` — the German and
  Russian `voice_*` lines, same resource names, so `R.raw.voice_tier_3`
  resolves per locale automatically

Resource names are lowercase `a-z0-9_` only, as Android requires.

## The three characters

`voices_post.py` turns a flat TTS take into one of three cartoon characters.
The assignment per line lives in `voice_lines.json`.

| character | treatment | lines |
|---|---|---|
| **GRUFF** — hoarse big monster | tape shift −3.5 st with formants pushed a further −3 st (bigger head), octave-down subharmonic, 34 Hz growl AM, envelope-excited breath noise, soft-clip drive, +4.5 dB at 240 Hz / −4 dB at 2.6 kHz, downward grumble at the end | `voice_too_early`, `voice_tier_1` |
| **SQUEAKY** — tiny hyper monster | tape shift +9.5 st with formants raised only +5 st (so it stays a *voice*, not a chipmunk artefact), 7.5 Hz vibrato, airy breath, +3.5 dB presence at 3.2 kHz, high-pass at 220 Hz, rising tail | `voice_tier_2`, `voice_tier_4` |
| **SHOUTY** — excited announcer | tape shift +4.5 st / formants +2 st, 4:1 compression with makeup, stressed-vowel elongation, presence boost, light saturation, rising exclamation glide | `voice_ready`, `voice_tier_3`, `voice_tier_5`, `voice_new_record` |

### Why "tape shift" and not a pure phase vocoder

A naive "pitch up 11 semitones, keep the duration" needs a ~1.9× WSOLA stretch,
and that is exactly what makes processed speech sound metallic and smeared —
it was clearly visible as vertical striping in the first spectrograms. Instead
`synth.tape_shift()` resamples (which moves pitch *and* formants and changes
the duration), optionally puts back only **part** of the duration change
(`restore=0…1`), and then corrects the spectral envelope with a cepstral
`formant_warp`. A lumbering monster *should* be slower and a tiny one *should*
be faster, so GRUFF uses `restore=0` (no stretching at all) and the others stay
at a mild ~1.15–1.35× factor.

## Using real recordings or neural TTS instead of SAPI

Drop a file at:

```
tool/audio/recordings/<line_id>_<lang>.wav      e.g. voice_tier_5_ru.wav
```

and re-run `python tool/audio/voices_post.py`. That file is used as the raw
take instead of the SAPI one, for that line and language only — you can mix
and match freely.

* **Any sample rate and channel count is accepted**; it is converted to mono
  22050 Hz automatically.
* An expressive source needs less help, so recordings get
  `RECORDING_DSP_AMOUNT = 0.55` instead of `1.0`, and **time warping is
  switched off** so a performance that already has comic timing is not
  stretched twice.
* Override per line by adding `"dsp_amount": 0.3` to that line's entry in
  `voice_lines.json`. `0` = character chain off (only trim/normalise).

This is the drop-in path used by the local neural-TTS (Chatterbox) setup; see
`README_neural_voices.md` if that agent has written one.

## The `vox_*` monster vocalisations

Language-independent gibberish, synthesised from scratch (no TTS) with a
source–filter model: a Rosenberg glottal pulse train with jitter and shimmer,
optional rasp and breath noise, driven through four moving formant resonators
that morph between vowel targets. Reference point is Banjo-Kazooie / Minions
gibberish — funny first, realistic second.

`vox_ouch_1..4` (ow / oof / eek / bleh), `vox_laugh_1..2` (taunting he-he-he),
`vox_yay`, `vox_aww`, `vox_pop_hello_1..2`.

> **`vox_pop_hello_*` are optional and must NOT be played at the target onset
> by default.** They are long and vowel-shaped, so their perceived onset is
> fuzzy; playing them when the face appears would add variance to the measured
> reaction time. Use them for idle/taunt animations between targets instead.

## QA

`analyze.py` prints, per file: sample rate, duration, size, peak dBFS, RMS,
weighted RMS (300 Hz–6 kHz, the perceived-loudness proxy), 10→90 % attack time,
DC offset, clipped-sample count and the WAV format tag; for the loops it also
reports the wrap-point discontinuity **relative to the 99.9th percentile of the
interior sample-to-sample deltas** (an absolute number alone says nothing).

It fails the run if any of these break:

* a file is not PCM tag 1 / 16-bit
* a file is silent, DC-offset, or has clipped samples
* a loop's wrap delta exceeds the interior p99.9
* **target-pop fairness**: attack < 5 ms on all three, first-30 ms samples
  bit-identical across the three, first-50 ms RMS spread ≤ 0.5 dB

Spectrogram PNGs land in `_preview/` — look at them, they catch swapped tiers,
dead files and phase-vocoder smearing that a numbers table does not.

It also prints a **suggested playback gain per file**, computed from the
measured weighted RMS against a per-group reference
(`gain = 10^((ref − wRMS)/20)`, clamped to 1.0). Use those numbers for the
`SoundPool.play()` volumes instead of guessing. Note that `sfx_target_pop_*`,
`sfx_miss` and `sfx_ui_click` are *intentionally* below their group reference —
they are cues, not events — so ignore the "needs a boost" flag on those three.

Note on file size: `.wav` is on aapt2's default `noCompress` list (the existing
`res/raw/*.ogg` are verifiably `STORED` in `app-release.apk`), so these assets
land in the APK/AAB uncompressed. Budget the full ~4.44 MB as download size.

## Loudness policy

Peaks are normalised to about −3 dBFS, but where a group of cues plays in the
same context the **weighted RMS** is matched instead, so nothing jumps out;
that is why some jingles peak at −5 to −7 dBFS. The three target pops are a
special case: they are scaled by **one shared gain** (`synth.normalize_group`),
never individually, because per-file normalisation would make the variants
differ in onset loudness and bias the reaction-time measurement.

## Loop construction

Both music loops are written into a buffer of exactly
`bars × 4 × 60/BPM × sr` samples, and every note is placed with **wrap-around
indexing** (`synth.place(..., wrap=True)`), so note releases and the reverb
tail fold back onto the start of the loop. The loop point is therefore
click-free by construction rather than by a fade, which would be audible.

| loop | key | BPM | bars | length | progression |
|---|---|---|---|---|---|
| `music_menu` | C major | 100 | 12 | 28.8 s | `\| C \| Am \| F \| G7 \|` × 3, each pass with a different melodic motif and a fuller drum pattern |
| `music_game` | A minor | 120 | 16 | 32.0 s | `\| Am \| F \| C \| G \|` × 4, sparse; four different two-note motifs, the last one an octave up |

### Playing the loops back — do NOT use `MediaPlayer.setLooping(true)`

`MediaPlayer.setLooping()` is not gapless on Android; it inserts an audible
glitch at the boundary, which throws away the sample-accurate wrap construction
above. Use **`AudioTrack` in `MODE_STATIC`** with
`setLoopPoints(0, frameCount, -1)` — that is sample-accurate, and the decoded
buffers here are only 1.27 MB / 1.41 MB. Music also needs its own
`AudioAttributes` (`USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`), not the existing
`GameAudio` pool's `USAGE_GAME` + `CONTENT_TYPE_SONIFICATION`.

`music_game` is deliberately restrained: the lead has a ≥30 ms attack, there is
no snare and no bright accent, so **nothing in the music can be mistaken for
the target-appears cue**, which is the one sound in the game with a <5 ms
attack.
