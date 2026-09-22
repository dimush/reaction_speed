# Neural voice takes (Chatterbox Multilingual)

Raw, **un-characterised** takes for the audio pipeline's DSP chain.

- Format: **mono, 24 000 Hz, 16-bit PCM WAV**, silence-trimmed, peak-normalised to **-3 dBFS**.
  Note `synth.py` uses `SR_TONAL = 22050` for voices — **these files are 24 kHz** (Chatterbox's native rate, not resampled so the DSP chain gets the original). Resample on load.
- Chosen takes: `tool/audio/recordings/<line>_<lang>.wav`
- Rejected takes: `tool/audio/recordings/_alternates/` (git-ignored), raw untrimmed in `_alternates/raw/`
- Regenerate: see `README_neural_voices.md`

## Chosen take per line

| resource | canonical text | take | prompt text used | duration s (plausible range) | syllable nuclei / expected | max internal silence s | F0 range (semitones) | ASR similarity | ASR transcript |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `voice_ready_en` | Ready? | t2 | Reeeady?! | 0.56 (0.32–1.95) | 2/2 | 0.00 | 7.9 | 1.00 | `Ready?` |
| `voice_ready_de` | Bereit? | t0 | Bereit?! | 0.80 (0.32–1.95) | 3/2 | 0.03 | 14.8 | 0.62 | `Aber, right?` |
| `voice_ready_ru` | Готов? | t2 | Гото-о-ов?! | 0.69 (0.32–1.95) | 2/2 | 0.00 | 5.7 | 1.00 | `Готов!` |
| `voice_too_early_en` | Too early! | t3 | Too early! | 1.00 (0.48–2.50) | 3/3 | 0.01 | 1.9 | 1.00 | `too early.` |
| `voice_too_early_de` | Zu früh! | t2 | Ach, zu früh! | 1.03 (0.48–2.50) | 3/3 | 0.04 | 7.4 | 1.00 | `Ach zu früh!` |
| `voice_too_early_ru` | Рано! | t1 | Рано!! | 0.71 (0.32–1.95) | 2/2 | 0.00 | 8.8 | 1.00 | `Рано!` |
| `voice_tier_1_en` | Keep practicing! | t0 | Keep practicing!! | 1.24 (0.64–3.05) | 4/4 | 0.09 | 9.1 | 1.00 | `Keep practicing!` |
| `voice_tier_1_de` | Weiter üben! | t2 | Tja, weiter üben! | 1.46 (0.80–3.60) | 5/5 | 0.26 | 9.8 | 0.97 | `Ja, weiter üben!` |
| `voice_tier_1_ru` | Тренируйся! | t0 | Тренируйся!! | 0.99 (0.64–3.05) | 4/4 | 0.00 | 7.5 | 1.00 | `Тренируйся!` |
| `voice_tier_2_en` | Not bad! | t1 | Not bad!! | 0.56 (0.32–1.95) | 2/2 | 0.03 | 7.3 | 1.00 | `Not bad.` |
| `voice_tier_2_de` | Nicht schlecht! | t1 | Nicht schlecht!! | 0.73 (0.32–1.95) | 2/2 | 0.00 | 7.5 | 1.00 | `Nicht schlecht!` |
| `voice_tier_2_ru` | Неплохо! | t3 | Неплохо! | 0.78 (0.48–2.50) | 3/3 | 0.02 | 5.2 | 1.00 | `Неплохо!` |
| `voice_tier_3_en` | Fast! | t1 | Fast!! | 1.00 (0.30–1.40) | 2/1 | 0.27 | 5.5 | 1.00 | `Fast.` |
| `voice_tier_3_de` | Schnell! | t0 | Schnell!! | 0.56 (0.30–1.40) | 2/1 | 0.00 | 8.7 | 1.00 | `Schnell!` |
| `voice_tier_3_ru` | Быстро! | t4 | Ух, быстро! | 1.15 (0.48–2.50) | 3/3 | 0.14 | 7.7 | 1.00 | `Ух, быстро!` |
| `voice_tier_4_en` | Lightning! | t0 | Lightning!!! | 0.68 (0.32–1.95) | 2/2 | 0.02 | 9.3 | 1.00 | `Lightning.` |
| `voice_tier_4_de` | Blitzschnell! | t2 | Wow, blitzschnell! | 0.95 (0.48–2.50) | 3/3 | 0.08 | 6.9 | 0.97 | `Wow, Blitzchnell!` |
| `voice_tier_4_ru` | Молния! | t1 | Молния!!! | 0.89 (0.48–2.50) | 2/3 | 0.00 | 9.5 | 1.00 | `Молния` |
| `voice_tier_5_en` | Superhuman! | t3 | Superhuman! | 0.89 (0.64–3.05) | 4/4 | 0.00 | 4.6 | 1.00 | `Superhuman.` |
| `voice_tier_5_de` | Übermenschlich! | t6 | ÜBERMENSCHLICH!!! | 0.82 (0.64–3.05) | 4/4 | 0.00 | 10.4 | 0.92 | `Übermenschli.` |
| `voice_tier_5_ru` | Невероятно! | t4 | Невероя-я-ятно!!! | 1.45 (0.80–3.60) | 5/5 | 0.11 | 8.0 | 0.90 | `Невероятна!` |
| `voice_new_record_en` | New record! | t1 | NEW RECORD!!! | 0.81 (0.48–2.50) | 3/3 | 0.00 | 4.8 | 1.00 | `New record!` |
| `voice_new_record_de` | Neuer Rekord! | t3 | Neuer Rekord! | 0.89 (0.64–3.05) | 4/4 | 0.00 | 9.0 | 1.00 | `Neuer Rekord.` |
| `voice_new_record_ru` | Новый рекорд! | t0 | НОВЫЙ РЕКОРД!!! | 1.06 (0.80–3.60) | 4/5 | 0.09 | 4.7 | 1.00 | `новый рекорд.` |

## Needs a human listen

Nobody listened to these takes; selection was entirely objective. The lines below tripped at least one check and are the ones worth spot-checking by ear first (re-roll with `generate --only <line>_<lang> --takes 8`, then `select`).

- `voice_ready_de` — REJECTED after 8 takes (all stressed the wrong syllable: `Aber, right?`, `Breit`, `Bereit? Bereit?`); the pipeline falls back to the SAPI Hedda take for this line. File kept in `_alternates/rejected/`.
- `voice_tier_1_de` — internal silence 0.26 s
- `voice_tier_3_en` — internal silence 0.27 s

## Metric meanings

- **duration**: plausible range is `0.16·syllables … 0.55·syllables + 0.85` s.
- **syllable nuclei**: peaks of the smoothed energy envelope within 18 dB of its maximum and ≥ 130 ms apart — a proxy for "the model said the right number of things".
- **max internal silence**: longest gap between active-speech segments after trimming; large values indicate a hallucinated second utterance.
- **F0 range**: 20th–80th percentile of a median-filtered, octave-error-guarded autocorrelation pitch track, in semitones — expressiveness proxy (flat reads score near 0).
- **ASR similarity**: best `difflib` ratio of a `whisper-small` transcript against the prompt text *or* the canonical resource line. Used as a scoring signal only, never as a sole rejector (whisper-small is weak on one-word utterances, especially in Russian).

Full per-take metrics: `_alternates/metrics.json`.
