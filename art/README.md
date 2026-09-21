# Art pipeline — Reaction Speed (Фаза 7)

All raster art in this project was generated **locally** on the owner's own
ComfyUI server (SDXL-Turbo checkpoint, `sd_xl_turbo_1.0_fp16.safetensors`,
LAN host `192.168.0.243:8188`) via
`~/.claude/skills/comfy-image-gen/scripts/comfy-generate.sh`, then
post-processed locally with Python/PIL/numpy/scipy. **No third-party or
downloaded stock assets are used anywhere in this app.** The achievement
badge glyphs (stars, lightning bolt, checkmark, level icon, flag) and all
text (achievement numbers, feature-graphic title) are drawn/rendered
directly with PIL — not diffusion output — using the Windows-bundled
`impact.ttf` / `arialbd.ttf` fonts, since SDXL-Turbo text rendering is
unreliable.

Note on reproducibility: `comfy-generate.sh` picks a random seed per run
and does not print it, so the exact pixels below are not re-derivable from
the prompt alone — re-running a command will produce a similar but not
identical image. The prompts and pipeline steps are otherwise exactly as
used.

## Directory layout

- `art/raw/` — untouched SDXL-Turbo generations (PNG) and intermediate
  crops (e.g. `badge_base_crop.png`).
- `art/final/` — processed masters (WebP with alpha where relevant); this
  is also what got copied into `app/src/main/res/drawable-nodpi/`.
- `art/contact_sheet.png` — every shipped asset composited on a dark-green
  background, for a final visual QA pass.
- `tool/art/*.py` — reusable, rerunnable post-processing scripts (see
  below).
- `store/achievements/*.png` — 512×512 opaque Play Console achievement
  icons.
- `store/feature-graphic.png` — 1024×500 Play listing feature graphic.

## 1. Target faces (10×, `target_face_01..10.webp`, 256×256, circular alpha)

Base prompt template (SDXL, 512×512), varied by expression clause:

```
cute funny round red monster face, cartoon game sprite, head shaped like a
perfect smooth ball, no horns, no spikes, no ears, no hair, bald round head,
thick black outline, vibrant red skin, small character centered with
margin, plain white background, no text, 2d mobile game art icon
```

Expression clause used in the **final, shipped** generation of each file
(several faces needed 2–3 reroll passes — the "as-shipped" column is what
actually reproduces `art/raw/target_face_NN.png`, not earlier rejected
attempts):

| File | Expression clause (as shipped) | Actual result vs. original intent |
|---|---|---|
| `target_face_01` | big goofy eyes, silly grin with teeth | matches intent (grinning) |
| `target_face_02` | tongue sticking out playfully, one eye winking | rendered as a plain closed-mouth grin, no visible tongue/wink — kept as a distinct "content smile" variant |
| `target_face_03` | eyes crossed looking at nose, tongue out silly face | rendered as a fanged open grin, not visibly cross-eyed — kept as a distinct "toothy" variant |
| `target_face_04` | big surprised wide open eyes, small round open mouth, shocked expression | rendered as a sweaty/startled face with decorative corner swirls (outside the circle, cropped away) — matches intent (surprised) |
| `target_face_05` | sleepy droopy half-closed eyes, lazy smile, yawning | rendered as a calm neutral grin, only mildly droopy — kept as intended (sleepy/content) |
| `target_face_06` | one eye winking shut, cheeky smirk grin | rendered with tongue out and a small hair tuft, not clearly winking — kept as a "tongue out" variant |
| `target_face_07` | angry annoyed expression, furrowed eyebrows, gritted teeth | matches intent (angry-cute), small horn-like tufts present but stay inside the cropped circle |
| `target_face_08` | one eye winking shut, cheeky smirk grin | rendered as a wide-eyed nervous/startled face, not winking — kept as a distinct variant (cyclops was attempted 3x and never rendered correctly, so this concept was dropped) |
| `target_face_09` | huge open mouth grin with lots of small pointy teeth | matches intent (toothy) |
| `target_face_10` | goofy dopey expression, wonky uneven eyes, silly loose grin | matches intent (derpy) |

Rerolls were needed because early prompt variants produced either
horned/spiked silhouettes, edge-to-edge close-ups with no background margin
(uncroppable), a non-white/maroon background (breaks flood-fill bg
removal), or SDXL rendering a *grid of many small faces* instead of one
character — dropping verbose "not a group / no crowd" negation-style
wording (which seemed to trigger the multiplication) and keeping the
prompt short and single-subject fixed most of these.

**Fairness/pipeline (`tool/art/circle_crop.py` + `tool/art/remove_white_bg.py`):**
1. Background removed via **edge flood-fill** (not a global brightness
   threshold) so interior light pixels — teeth, eye whites — are never
   touched, only pixels connected to the border are cut.
2. Bounding circle of the alpha mask computed; image cropped/scaled so the
   face circle **touches all 4 edges** of the 256×256 canvas, with a 2px
   Gaussian-blurred alpha edge (feather).
3. Per-face mean luminance measured over the opaque disc; each face's RGB
   is scaled (±5% max) toward the group median so all 10 read as equally
   bright/salient reaction-test stimuli. The computed table is printed by
   the script on every run.

**Convention for the integrator:** the face circle exactly touches the
image edges (top/bottom/left/right) — no built-in margin. If the game
renderer wants breathing room around the sprite, add it in code, not by
re-cropping.

## 2. Verdict mascots (`mascot_tier_1..5_*.webp`, 512×512, transparent)

Common style suffix: `sticker style, thick black outline, vibrant colors,
funny cartoon character, full body, simple pose, centered, 2d mobile game
art, plain white background, no text`.

| File | Subject prompt |
|---|---|
| `mascot_tier_1_sloth` | cute sleepy sloth character sitting, half-closed eyes, yawning, relaxed slouch |
| `mascot_tier_2_turtle` | cute determined turtle character wearing red sneakers, running pose, confident grin, green shell |
| `mascot_tier_3_rabbit` | cute fast rabbit character running, motion pose, excited grin, floppy ears flying back |
| `mascot_tier_4_cheetah` | cute cheetah character sprinting fast, motion speed lines, sunglasses, big grin, spotted fur |
| `mascot_tier_5_hero` | cute cartoon superhero lightning bolt character with cape and mask, flying pose, arms out, glowing yellow, heroic grin |

Processed with `tool/art/process_mascot.py`: edge flood-fill background
removal → despeckle (keep largest connected alpha component only) → crop to
content bounding box with a small margin → resize to 512×512 square WebP.

## 3. Home mascot (`mascot_home_dino.webp`, 512×512, transparent)

```
cute friendly green cartoon dinosaur character pressing a big red button
with one finger, excited grin, sticker style, thick black outline, vibrant
colors, full body, simple pose, centered, 2d mobile game art, plain white
background, no text
```

Same `process_mascot.py` pipeline as the tier mascots.

## 4. Grass tile (`grass_tile.webp`, 512×512, opaque)

Raw generation:
```
top-down grass ground texture for video game, painterly brushstrokes,
olive green, subtle random mottling, uniform pattern, edge to edge, no
border, no vignette, no objects, no text, flat lighting
```

Made seamless and toned down with `tool/art/make_seamless_grass.py`:
1. Offset the 512×512 image by half-width/half-height (wraparound) so the
   four original corners meet at the center — this turns the un-tileable
   edges into a visible cross-seam in the middle instead.
2. Blend a softly Gaussian-blurred copy over a **wide, quadratically
   feathered** band around that cross (no hard band edge) so the seam
   reads as natural texture variation, not a line. (An earlier version
   with a narrower/harder-edged band left a faint but visible grid when
   tiled 3×3 — verified by actually tiling it, not by eyeballing the
   single 512 tile, per the QA note below.)
3. Brightness/contrast/saturation reduced (`Enhance.Brightness 0.82,
   Contrast 0.78, Color 0.85`) to land on a mid-dark, low-contrast green so
   red targets stay the highest-salience thing on screen.
4. Verified by actually tiling the result 3×3 (`_tiled3x3_check.png`,
   deleted after inspection) and by compositing a red target face on top —
   both look correct; see QA section below.

## 5. Achievement icons (`store/achievements/*.png`, 512×512, opaque)

Per advisor guidance: SDXL-Turbo cannot reliably render the required
numbers (220/250/300/350) or produce 10 mutually-consistent-yet-distinct
badge designs, so only **one** badge base was generated and reused:

```
round game achievement badge medal icon, blank empty center circle, gold
rim, ribbon, glossy sticker style, thick outline, simple flat design, no
text, no gems, no symbols, centered, plain white background
```

(SDXL returned a grid of several badge variants; the clean blank
top-left circle was cropped out as `art/raw/badge_base_crop.png`.)

`tool/art/make_achievement_icons.py` then, per achievement:
1. Recolors the gold badge via HSV hue-rotation (bronze / silver / gold /
   platinum / diamond / green / blue / purple / teal / red) to give each
   tier a distinct, consistent-style rim color.
2. Draws a simple glyph with PIL primitives — 5-point star (series
   achievements), lightning bolt (speed achievements), checkmark
   (flawless), flag (first series), or a level/balance icon (steady hand).
3. Renders the speed-threshold number (`220`/`250`/`300`/`350`) or ordinal
   (`1st`) with Impact TTF, dark-brown fill + stroke for contrast against
   the gold/silver rim.
4. Flattens onto an opaque white canvas (Play Console icons need no alpha).

| Resource | Tier color | Glyph |
|---|---|---|
| `achievement_first_series` | green | flag + "1st" |
| `achievement_under_350` | bronze | lightning + "350" |
| `achievement_under_300` | silver | lightning + "300" |
| `achievement_under_250` | gold | lightning + "250" |
| `achievement_under_220` | platinum | lightning + "220" |
| `achievement_series_10` | blue | star + "10" |
| `achievement_series_50` | purple | star + "50" |
| `achievement_series_200` | diamond | star + "200" |
| `achievement_steady_hand` | teal | level/balance icon |
| `achievement_flawless` | red | checkmark |

## 6. Feature graphic (`store/feature-graphic.png`, 1024×500)

Composed entirely in PIL (`tool/art/make_feature_graphic.py`) from
already-finished pieces: the seamless grass tile (repeated to fill the
canvas, top strip darkened for text contrast), three target faces, the
home dino mascot, and the title "Reaction Speed" + a one-line subtitle set
in Impact with a white fill and dark-green stroke outline for legibility
over the grass texture.

## Post-processing scripts (`tool/art/`)

- `remove_white_bg.py` — edge flood-fill background removal (border-connected
  near-background-color pixels only) + feather + despeckle. Shared by the
  other scripts; never a global brightness threshold, so interior white
  (teeth, sneaker soles, eye whites) is preserved.
- `circle_crop.py` — target-face-specific: bounding-circle crop that fills
  the frame, plus the brightness-fairness normalization pass.
- `process_mascot.py` — generic sticker/mascot cutout: bg removal →
  despeckle → bbox crop with margin → square resize.
- `make_seamless_grass.py` — offset-and-blend seamless tiling + tone-down
  + a 3×3 tile preview for verification.
- `make_achievement_icons.py` — badge recoloring + PIL glyph/number
  composition for all 10 achievement icons.
- `make_feature_graphic.py` — composes the 1024×500 Play listing graphic.
- `contact_sheet.py` / `composite_check.py` — QA helpers: grid multiple
  images onto a plain or dark-green background for fast visual review
  (used throughout instead of `Read`-ing every raw generation
  individually).

## QA performed

- Every final asset was visually reviewed via contact sheets (raw
  generations in batches of ~10, then again after processing).
- All alpha-cutout assets (faces, mascots) were composited onto a dark
  green background and inspected for white halos at the edges — none
  found; `remove_white_bg.py`'s border-flood-fill approach avoids the
  halo failure mode of a global-threshold cutout.
- The grass tile was checked by actually tiling it 3×3 (not by looking at
  the single 512 tile) and by placing a red target face on top to confirm
  the "targets pop" requirement.
- `art/contact_sheet.png` shows every shipped asset (target faces,
  mascots, grass tile, achievement icons, feature graphic) composited on
  a dark-green background in one grid, generated fresh at the end of the
  pass.

## Known imperfections (for the integrator / owner)

- A few target faces (e.g. `03`, `08`) drifted from their originally
  intended expression label (cross-eyed→toothy-fanged, cyclops→nervous)
  after repeated SDXL rerolls; all 10 are still visually distinct,
  predominantly red, similar size/brightness, and fill the circle — the
  fairness constraint that actually matters for the reaction-test use
  case is intact.
- `mascot_home_dino` shows the dino reaching/gesturing rather than
  literally touching a red button (SDXL did not render the button prop
  reliably in the accepted generation); it still reads as a friendly,
  inviting pose for the home screen.
- `mascot_tier_1_sloth` and `mascot_tier_2_turtle` kept incidental prop
  details (a tree branch/leaves behind the sloth) that were not explicitly
  requested but add charm without hurting the transparent-cutout use case.

## License

All assets were generated locally by the app owner using a self-hosted
SDXL-Turbo model, plus original PIL/numpy code in this repo. No
third-party or network-downloaded art, fonts (beyond the OS-bundled
Windows fonts used only for text rendering), or stock assets were used.
