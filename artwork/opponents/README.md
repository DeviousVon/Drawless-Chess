# Opponent portrait source and provenance

These seven fictional opponent portraits were created for Drawless Chess on
2026-07-13 with OpenAI's built-in image-generation tool. No photograph, stock
asset, celebrity likeness, or third-party character artwork was supplied as a
reference. Theo was generated first; his resulting image was used only as the
rendering-style, camera, crop, and lighting reference for the other six
characters.

The original 1254 x 1254 RGB PNG outputs are retained in `source/`. The Android
resources in `android/app/src/main/res/drawable-nodpi/` are 512 x 512 lossy WebP
derivatives made with Pillow using Lanczos resizing, quality 88, method 6, and
no EXIF or ICC metadata. `contact-sheet.jpg` is a review aid, not an Android
runtime resource.

To the extent copyright or related rights subsist in these outputs, they are
included in the project's GPL-3.0-or-later grant. Character names, biographies,
selection UI, and integration code are original project material.

## Character direction

| Level | Character | Personality |
| --- | --- | --- |
| Learner | Mira | Bright, curious, fearless about trying a new idea. |
| Casual | Theo | Warm, observant, relaxed, and gracious about the result. |
| Challenger | Rhea | Playfully competitive and energized by resistance. |
| Club | Mateo | Patient, sociable, and ready with a post-game story. |
| Expert | Yuna | Precise, composed, and dryly funny. |
| Master | Amara | Disciplined, gracious, and unshakable under pressure. |
| Grandmaster | Lucian | Formidable, courteous, focused, and sparse with words. |

## Final prompt set

Every portrait used this shared production brief:

```text
Use case: stylized-concept
Asset type: square mobile game opponent avatar
Scene/backdrop: a softly blurred chess-club setting with restrained colored
bokeh and subtle out-of-focus chess shapes only.
Style/medium: premium high-end 3D animated feature-film realism; lifelike skin,
hair, eyes, and fabric texture; appealing stylization, not a photograph and not
cartoon-flat.
Composition/framing: one fictional adult, square 1:1 centered head-and-shoulders
portrait, eye-level and facing the viewer, with all hair and shoulders safely
inside the frame; designed for circular and rounded-square crops at 48–120 dp;
strong silhouette and a thumbnail-readable expression.
Constraints: one adult character only; no hands or face obstruction; no text,
letters, numbers, logos, brands, frame, border, UI, or watermark.
Avoid: real-person likenesses, uncanny photorealism, plastic skin, exaggerated
caricature, stereotypes, anime, flat vector art, and busy backgrounds.
```

The six portraits after Theo additionally used:

```text
Input images: Image 1 is Theo's portrait and is a style, rendering-quality,
camera, crop, and lighting reference only. Do not preserve its person, identity,
gender presentation, clothing, or colors. The new subject must be a visibly
different fictional person while belonging to exactly the same game portrait
collection.
```

Character-specific prompt blocks:

### Mira — Learner

```text
Subject: a white woman in her late 20s with a short halo of tousled auburn
curls, light freckles, bright green eyes, and a delighted curious smile; mustard
cardigan over a soft sage shirt; optimistic, fearless about trying new ideas,
and visibly eager to learn.
Backdrop/palette: welcoming neighborhood chess club; muted coral, sage,
mustard, and warm natural skin tones.
Lighting/mood: warm, fresh, and curious with a soft key light and cool rim.
Avoid: childish or underage appearance.
```

### Theo — Casual

```text
Subject: a friendly Black man in his early 30s with short textured hair, a
neatly trimmed beard, expressive warm brown eyes, and a relaxed half-smile;
comfortable dark-teal knit overshirt; easygoing and observant, the kind of
player who enjoys a clever move and never takes a loss personally.
Backdrop/palette: contemporary chess cafe; warm amber, deep teal, and natural
skin tones.
Lighting/mood: welcoming and relaxed with a warm key light and cool rim.
```

### Rhea — Challenger

```text
Subject: a South Asian woman in her early 30s with warm brown skin, sharp dark
eyes, a swept-back wavy black bob, one subtly raised eyebrow, and a confident
playful half-smile; tailored burgundy bomber-style jacket over charcoal; bold,
competitive, and visibly delighted by a challenge.
Backdrop/palette: modern evening chess lounge; burgundy, plum, crimson,
charcoal, and warm natural skin tones.
Lighting/mood: energetic and self-assured with a warm key and restrained
magenta-violet rim.
Avoid: aggression or villain styling.
```

### Mateo — Club

```text
Subject: a Latino man in his mid-40s with olive-brown skin, salt-and-pepper
wavy hair, a short beard, kind dark eyes behind tasteful thin round glasses,
and an assured sociable smile; rust overshirt over moss knitwear; patient,
good-humored, experienced, and ready with a story after the game.
Backdrop/palette: established community chess club; rust, moss green, walnut,
and warm natural skin tones.
Lighting/mood: grounded, welcoming, and quietly capable.
Constraint: glasses must not obscure the eyes.
```

### Yuna — Expert

```text
Subject: an East Asian woman in her late 30s with a precise chin-length black
bob tucked behind one ear, intelligent dark-brown eyes behind elegant slim
rectangular glasses, and a composed nearly-smiling expression suggesting dry
wit; structured midnight-blue jacket over pale slate; analytical and patient.
Backdrop/palette: refined chess study; midnight indigo, slate, icy blue, and
natural skin tones.
Lighting/mood: cool, precise, and calm with a small warm catchlight in the eyes.
Avoid: emotionless or robotic expression; glasses must not obscure the eyes.
```

### Amara — Master

```text
Subject: a Black woman in her early 50s with deep brown skin, expressive dark
eyes, a sculptural braided updo with natural silver strands, high cheekbones,
and a calm confident expression with the faintest knowing smile; elegant plum
jacket over a warm-gold blouse; disciplined, gracious, focused, and never
rattled under pressure.
Backdrop/palette: elegant chess salon; plum, muted gold, deep burgundy, and
natural skin tones.
Lighting/mood: commanding but humane with a warm gold key and burgundy rim.
Avoid: crown, royal costume, or villain styling.
```

### Lucian — Grandmaster

```text
Subject: a white man in his early 60s with fair weathered skin, swept-back
silver hair, a closely trimmed silver beard, clear steel-blue eyes, and a calm
penetrating expression softened by a small respectful smile; immaculate
charcoal turtleneck and dark-navy jacket; formidable concentration, sparse
words, deep courtesy, and the sense that he has considered every reply.
Backdrop/palette: quiet tournament hall after dusk; graphite, deep navy,
silver, restrained antique gold, and natural skin tones.
Lighting/mood: formidable yet respectful, with a cool controlled key,
antique-gold rim, and warm eye catchlights.
Avoid: crown, trophy, arrogance, fantasy costume, or villain styling.
```

## SHA-256 inventory

| Level | Source PNG SHA-256 | Android WebP SHA-256 |
| --- | --- | --- |
| learner | `f05c78cded7cd8a6a95594b5b80827ca63a6c0b6fd21576159a08027be9b8de6` | `391aeae611c727234341d25d46b47313c968eb5673316999cf949cdd1c13cb04` |
| casual | `fc4dadf4ca11e80ab0ea92d98e53ff57a25f91c0fcce426c8e85d46f4511188f` | `0bd23dde8bd49442350a8ed58853472861fb44cca3a0c5aa9e7add349dd718b4` |
| challenger | `106c54c1f05029106e0415458657107b8efbed9a7d123a3daf04c6c9985ca32c` | `0d8eebd31d43db0cecb46f3e954f48e4fd501971b91b1a7427f0440d9b787485` |
| club | `cf4a5374efebab82add661f3b6e14f6d6c0df6c249571272722ffb1eb3aa8d94` | `747a559be0213b9274d84296064553ed3f845b80a7e8274a3647719400354210` |
| expert | `a2e13ca26127459a17efc1073d0b632825295974cc13b99d0c8845a7abddb822` | `eb10e27325f875dd1e428f894b5759b09bea9135c21926b6cfdb2d02537d97cb` |
| master | `0b76e752406c26ec61a297812e2313db118b1234d661928abdd6bdf2a79a2d66` | `9853d4a91ee22119a2e764e029d3bd9ea538602fa59493492cf3228f54fa119f` |
| grandmaster | `07bac7075aed9062e928f1c08f6661fadec6ac567b490de12d9c27bd9405c7b1` | `779d051404e56b5daee33a3c069f9dd663ba9d5b8218fb5932c7049d9c8363f4` |
