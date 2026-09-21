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
| `voice_ready_en` | Ready? | t0 | Ready?! | 0.52 (0.32–1.95) | 3/2 | 0.00 | 13.2 | n/a | — |
| `voice_ready_de` | Bereit? | t0 | Bereit?! | 0.80 (0.32–1.95) | 4/2 | 0.03 | 15.8 | n/a | — |
| `voice_ready_ru` | Готов? | t0 | Готов?! | 0.61 (0.32–1.95) | 3/2 | 0.00 | 15.7 | n/a | — |
| `voice_too_early_en` | Too early! | t0 | Too early!! | 0.94 (0.48–2.50) | 7/3 | 0.00 | 19.4 | n/a | — |
| `voice_too_early_de` | Zu früh! | — | **MISSING** | | | | | | |
| `voice_too_early_ru` | Рано! | — | **MISSING** | | | | | | |
| `voice_tier_1_en` | Keep practicing! | — | **MISSING** | | | | | | |
| `voice_tier_1_de` | Weiter üben! | — | **MISSING** | | | | | | |
| `voice_tier_1_ru` | Тренируйся! | — | **MISSING** | | | | | | |
| `voice_tier_2_en` | Not bad! | — | **MISSING** | | | | | | |
| `voice_tier_2_de` | Nicht schlecht! | — | **MISSING** | | | | | | |
| `voice_tier_2_ru` | Неплохо! | — | **MISSING** | | | | | | |
| `voice_tier_3_en` | Fast! | — | **MISSING** | | | | | | |
| `voice_tier_3_de` | Schnell! | — | **MISSING** | | | | | | |
| `voice_tier_3_ru` | Быстро! | — | **MISSING** | | | | | | |
| `voice_tier_4_en` | Lightning! | — | **MISSING** | | | | | | |
| `voice_tier_4_de` | Blitzschnell! | — | **MISSING** | | | | | | |
| `voice_tier_4_ru` | Молния! | — | **MISSING** | | | | | | |
| `voice_tier_5_en` | Superhuman! | — | **MISSING** | | | | | | |
| `voice_tier_5_de` | Übermenschlich! | — | **MISSING** | | | | | | |
| `voice_tier_5_ru` | Невероятно! | — | **MISSING** | | | | | | |
| `voice_new_record_en` | New record! | — | **MISSING** | | | | | | |
| `voice_new_record_de` | Neuer Rekord! | — | **MISSING** | | | | | | |
| `voice_new_record_ru` | Новый рекорд! | — | **MISSING** | | | | | | |

## Metric meanings

- **duration**: plausible range is `0.16·syllables … 0.55·syllables + 0.85` s.
- **syllable nuclei**: energy-envelope peaks ≥ −22 dB below frame peak, ≥ 90 ms apart — a proxy for "the model said the right number of things".
- **max internal silence**: longest gap between active-speech segments after trimming; large values indicate a hallucinated second utterance.
- **F0 range**: 10th–90th percentile of the autocorrelation pitch track, in semitones — expressiveness proxy (flat reads score near 0).
- **ASR similarity**: `difflib` ratio of a `whisper-small` transcript to the prompt text. Used as a scoring signal only, never as a sole rejector (whisper-small is weak on one-word utterances, especially in Russian).

Full per-take metrics: `_alternates/metrics.json`.
