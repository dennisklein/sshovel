# GSI Slidedeck — Design System

A brand & presentation design system for **GSI Helmholtzzentrum für Schwerionenforschung**
(GSI Helmholtz Centre for Heavy Ion Research) and its flagship project **FAIR**
(Facility for Antiproton and Ion Research), Darmstadt, Germany.

It exists to make on-brand **16:9 slide decks** quickly, plus the foundational
tokens, logo assets and UI primitives that keep any GSI material consistent.

---

## Source material

This system is built from two supplied artifacts:

- `uploads/Slide Template.pdf` — the official GSI slide master. A white 16:9 page
  with the **GSI wordmark top-right**, a **dark navy footer bar** carrying
  `<TITLE> | <Presenter>, <Venue/Event>, <DD.MM.YYYY>` on the left and the
  **slide number** on the right. The logo was extracted directly from this PDF.
- `uploads/GSI-FAIR_Gestaltungsrichtlinien.pdf` — the **official GSI/FAIR
  corporate-design guidelines (March 2023)**. This pins down the corporate
  font, the exact orange and harmonizing colour values, the colored-square /
  bar / line design elements, and logo clearspace rules — all applied here.

The extracted mark matches GSI's official 4C logo (orange "GSI" with the dot;
a fully-black variant also exists). GSI pairs with a separate **FAIR** logo and
is a member of the **Helmholtz Association**.

> **About the organisation:** GSI (founded 1969) operates a worldwide-leading
> heavy-ion accelerator facility; ~1,520 staff and ~1,000 visiting researchers/yr.
> Fields: nuclear & atomic physics, plasma physics, biophysics and ion-beam
> cancer therapy. FAIR is the new international accelerator centre being built at
> the site. The voice is that of a serious, international **basic-research lab**.

---

## Content fundamentals (voice & tone)

- **Register:** precise, factual, scientific. Confident but never promotional or
  salesy. Lets the science carry the weight.
- **Person:** institutional third person and the inclusive "we" of the research
  community ("At FAIR we recreate states of matter…"). Avoid marketing "you".
- **Casing:** sentence case for headings and UI ("What makes FAIR unique"), not
  Title Case. UPPERCASE only for short letter-spaced eyebrows/labels.
- **Numbers & units:** numerals with SI units, thin-space before the unit
  (`100 T·m`, `0.005–0.5 GeV/u`). Facility and instrument names are acronyms
  (UNILAC, SIS18, SIS100, ESR, FRS, CBM, PANDA, NUSTAR, APPA).
- **Bilingual reality:** the full legal name is German
  ("GSI Helmholtzzentrum für Schwerionenforschung"); decks and outward material
  are usually English. Keep umlauts correct.
- **Emoji:** none. This is a federal research institute — emoji are off-brand.
- **Example heading + line:**
  *"Probing the building blocks of matter" / "Heavy-ion research at the FAIR accelerator complex."*

---

## Visual foundations

- **Colour:** a tight, high-contrast palette. **White** surfaces, **black** ink
  (`#1A1A1A`, also the logo letters), a **dark navy** (`#292D35`, from the slide
  master's footer bar) for dark surfaces, and the official house colour — a warm
  **saffron orange** (`#FABE5D`; CMYK 0/30/70/0, RAL 1017, Pantone 142C). Per the
  guidelines the orange is **decorative** (the colored square, lines, the logo
  dot) — **body text stays black**, never light orange. For the rare small orange
  text need, use the derived legible amber `#B97800`. An official set of
  **harmonizing colours** (light red `#E85245`, ruby `#B10057`, dark blue
  `#005EA8`, light blue `#0E8BAE`, teal `#009478`, light green `#56AC36`) and
  grey `#EDEDED` round it out.
- **Web accessibility (WCAG AA):** the official palette is tuned for **print and
  decoration** — used as on-screen *text* the orange (1.67:1), green (2.86:1) and
  light red (3.67:1) fail WCAG 2.1 AA. A dedicated **Web UI layer**
  (`tokens/colors-web.css`, `--ui-*` tokens) supplies hue-matched, AA-safe
  companions: darkened versions for text/links/icons on white (all ≥4.5:1) and
  lightened versions for text on the navy surface (all ≥4.5:1), plus accessible
  button/state pairings. **Rule:** decorative fills & charts → brand `--gsi-*`;
  anything a user must read → `--ui-*`. The shared aliases (`--link`,
  `--text-accent`, `--focus-ring`, `--danger`…) are re-pointed at the accessible
  values, so existing components inherit AA contrast unchanged. See the **Web UI**
  group in the Design System tab for the specimen + ratios.
- **Typography:** set in **Helvetica** (Arial fallback) — the GSI/FAIR
  guideline-sanctioned font for screen, PowerPoint, Word and web (§2.2). Both are
  system fonts, so no webfont loads. The licensed corporate face **Linotype
  Syntax** can be layered on as an optional upgrade (see `tokens/fonts.css`).
  There is **no monospaced brand font**. Display is set
  large and **tight**; body is comfortable at 16–18px on screen, 23px on slides.
- **Layout:** orderly and generous. Content sits on a left margin (80px on
  slides); the logo is pinned top-right, the footer bar spans full width at the
  bottom. An **uppercase eyebrow → orange line → heading** stack is the canonical
  header pattern.
- **Shape language:** **angular**. The square is the brand's signature shape, so
  corner radii stay small (4–6px) and borders are crisp 1px hairlines. **Square
  (not round) bullet markers.**
- **Elevation:** restrained, cool-tinted shadows; prefer a 1px border over a
  shadow. The orange accent does the emphasis work, not depth.
- **Backgrounds:** mostly flat white or flat navy. No gradients except a
  bottom-up **protection scrim** over full-bleed photography (for legible
  reversed text). Imagery is treated full-bleed with the reversed logo on top.
- **Motion:** subtle and functional — short fades/translations
  (`120–360ms`, ease `cubic-bezier(0.2,0,0.1,1)`). Hover darkens fills; press
  nudges 1px down. No bounces, no decorative loops.
- **Transparency/blur:** minimal — only the photo scrim and a faint blur behind
  the footer when it sits over an image.

---

## Iconography

The supplied template contains **no icon set** — only the GSI wordmark, and the
guidelines define design *elements* rather than icons. So this system does **not**
ship an icon font or sprite, and you should **not** hand-draw SVG icons or use
emoji as icons. Approach:

- **Logo** is the one true brand mark — use the `Logo` component (positive /
  reversed) or `assets/gsi-logo*.png`. The mark is "GSI" with the **I** rendered
  as a stem topped by an orange dot. Keep **clearspace ≥ 1/5 of the logo's
  width/height** on every side (per guidelines §1.2).
- The brand's signature non-logo elements are **the colored square** (used as a
  bullet, a text-end marker, or decoration — in orange or any harmonizing
  colour), **grey horizontal bars** (page dividers / separators) and **colored
  horizontal lines**. These do the work icons would elsewhere.
- **If a deck genuinely needs UI icons,** substitute a neutral line set that
  matches the clean, technical feel — **Lucide** (CDN, 1.5–2px stroke) is the
  recommended stand-in. Flag any icon use as a substitution and keep it minimal.
- Unicode arrows (→) are acceptable inline affordances on buttons/links.

---

## Index / manifest

**Root**
- `styles.css` — the single entry point consumers link (import manifest only).
- `tokens/` — `colors.css`, `colors-web.css` (WCAG-AA web layer), `typography.css`,
  `spacing.css`, `elevation.css`, `fonts.css`, `base.css`.
- `assets/` — `gsi-logo.png` (positive), `gsi-logo-white.png` (reversed).
- `readme.md` (this file), `SKILL.md` (Agent-Skill wrapper).

**Components** (`window.GSISlidedeck_d090b6.<Name>`)
- `components/core/` — **Button**, **Tag**, **Stat**, **Card**.
- `components/brand/` — **Logo**.

**Foundation cards** (Design System tab) — `guidelines/`
- Colors: Brand core · Neutral ramp · Harmonizing colours.
- Web UI: Accessible text on white · Brand vs accessible · Accessible on navy · Buttons, links & states.
- Type: Display & headings · Body & lead · Type scale.
- Spacing: Spacing scale · Radius & elevation.
- Brand: Logo lockups · Slide footer bar · Accent motifs · Design elements.

**Slides** (Design System tab → "Slides", 1280×720) — `slides/`
- 01 Title · 02 Section divider · 03 Content · 04 Key figures ·
  05 Quote · 06 Two column · 07 Full-bleed image · 08 Closing.

**Templates** — `templates/gsi-deck/`
- **GSI Slide Deck** (`GsiDeck.dc.html`) — a working 6-slide deck with the
  navy footer bar; copy it to start a real presentation.

---

## Caveats / substitutions

- **Set in Helvetica / Arial.** The system uses **Helvetica** (Arial fallback) —
  the GSI/FAIR guideline-sanctioned font (§2.2) for screen / PPT / Word / web.
  Both are system fonts, so no webfont loads and the compiler reporting
  **0 embedded `@font-face` is correct**. The licensed corporate face **Linotype
  Syntax** is an optional upgrade: drop the `.woff2` files into `assets/fonts/`,
  uncomment the `@font-face` blocks in `tokens/fonts.css`, and prepend `'Syntax'`
  to `--font-sans` / `--font-display` in `tokens/typography.css`.
- **No product UI was supplied** — only the slide master + the corporate-design
  guidelines, no website/intranet, Figma or codebase. The system therefore
  centres on the **slide deck** plus foundations and primitives. Share gsi.de's
  design (Figma/code) and we'll add a product UI kit.
- **The FAIR logo** is referenced in the guidelines but wasn't supplied as a
  file; only the **GSI** mark is included. Share it to add FAIR + co-branded
  lockups.
- **Derived values** — UI states (button hover, the legible `#B97800` orange
  text, the neutral grey ramp) are reasoned extensions of the official palette,
  marked as derived in `tokens/colors.css`.
