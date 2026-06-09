# MasterClass — Style Reference
> Midnight Stage Presence

**Theme:** dark
**Source:** https://www.masterclass.com
**Category:** education / premium streaming
**Origin:** Refero Styles (https://styles.refero.design/)

MasterClass employs a dark, cinematic UI, reminiscent of a premium streaming platform. Dominant blacks and deep charcoals create a sophisticated environment, allowing vibrant accents to punctuate interactive elements and brand moments. Typography is bold and confident, commanding attention within the high-contrast setting. Component surfaces are subtle, often inset, maintaining a flat aesthetic that emphasizes content and celebrity figures over overt ornamentation.

## Tokens — Colors

| Name | Value | Token | Role |
|------|-------|-------|------|
| Pitch Black | `#000000` | `--color-pitch-black` | Primary background for footers and expansive content sections, subtle borders, text for badges |
| Charcoal Canvas | `#222326` | `--color-charcoal-canvas` | Default page background, card backgrounds |
| Graphite Base | `#0d0d0e` | `--color-graphite-base` | Background for subtle surface differentiation, text buttons, and darker borders |
| Deep Slate | `#272c33` | `--color-deep-slate` | Card backgrounds, button backgrounds for secondary actions |
| Subtle Ash | `#191c21` | `--color-subtle-ash` | Secondary background color for body sections |
| Muted Stone | `#211d0d` | `--color-muted-stone` | Text color for muted elements, borders on certain components |
| Iron Gray | `#43454c` | `--color-iron-gray` | Button backgrounds, inset shadow on interactive elements |
| Silver Mist | `#9ea0a9` | `--color-silver-mist` | Text color for secondary information, muted icons, disabled states |
| Light Steel | `#d4d5d9` | `--color-light-steel` | Subtle body text color, borders |
| Pure White | `#ffffff` | `--color-pure-white` | Primary text color, active states, badge backgrounds, clear button borders |
| Ghostly Gray | `#f4f4f5` | `--color-ghostly-gray` | Hover states for text and icons, borders for ghost buttons, light text |
| Action Raspberry | `#e32652` | `--color-action-raspberry` | Primary action buttons, prominent icons — a vibrant pop against dark backgrounds for conversion |
| Interactive Lime | `#dcff00` | `--color-interactive-lime` | Green action color for filled buttons, selected navigation states, and focused conversion moments. Use as a supporting accent, not as a status color |
| Highlight Gold | `#eed37f` | `--color-highlight-gold` | Accent borders for interactive cards or highlighted content, decorative elements |
| Subtle Cadet | `#596170` | `--color-subtle-cadet` | Background for small, less prominent interactive elements |

## Tokens — Typography

### Sohne — Primary typeface · `--font-sohne`
- **Substitute:** Inter
- **Weights:** 400, 600
- **Sizes:** 8px, 12px, 14px, 16px, 20px, 22px, 24px, 32px, 48px
- **Line height:** 1.00, 1.25, 1.33, 1.45, 1.50, 1.60, 2.50
- **Role:** Body text, navigation, links, buttons, headings up to 48px.

### Sohne Schmal — Display typeface · `--font-sohne-schmal`
- **Substitute:** Oswald or Anton
- **Weights:** 500
- **Sizes:** 64px, 80px
- **Line height:** 0.85, 0.90
- **Role:** Monumental headlines with tight letter-spacing for dramatic effect.

### Ivar Display Condensed — Serif accent · `--font-ivar-display-condensed`
- **Substitute:** Playfair Display SC or IBM Plex Serif
- **Weights:** 400
- **Sizes:** 64px
- **Line height:** 1.10
- **Role:** Classic authority accent for select feature titles.

### Type Scale

| Role | Size | Line Height | Token |
|------|------|-------------|-------|
| caption | 12px | 1.45 | `--text-caption` |
| body-sm | 14px | 1.45 | `--text-body-sm` |
| body | 16px | 1.45 | `--text-body` |
| body-lg | 20px | 1.45 | `--text-body-lg` |
| heading-sm | 22px | 1.45 | `--text-heading-sm` |
| heading | 24px | 1.3 | `--text-heading` |
| heading-lg | 32px | 1.3 | `--text-heading-lg` |
| display-sm | 48px | 1.3 | `--text-display-sm` |

## Tokens — Spacing & Shapes

**Base unit:** 4px · **Density:** comfortable

### Spacing Scale
4 / 8 / 12 / 16 / 20 / 24 / 32 / 48 / 64 / 80 / 96 / 112 (px)

### Border Radius
| Element | Value |
|---------|-------|
| cards | 8px |
| links | 8px |
| badges | 20px |
| images | 4px |
| inputs | 0px |
| buttons | 8px |

### Shadows
- subtle: `rgb(148, 154, 164) 0px 0px 0px 2px inset`
- subtle-2: `rgb(39, 44, 51) 0px 0px 0px 1px inset`
- subtle-3: `rgb(67, 69, 76) 0px 0px 0px 2px inset`
- subtle-4: `rgb(148, 154, 164) 0px 0px 0px 1px inset`

### Layout
- **Section gap:** 64px
- **Card padding:** 48px
- **Element gap:** 16px

## Components

### Ghost Navigation Button
Navigation/secondary action: `rgba(0,0,0,0)` bg, `#f4f4f5` text, `#f4f4f5` 1px border, radius 4px, padding 16px.

### Icon Button (filled)
Functional icons: `#596170` bg, `#ffffff` icon, radius 4px.

### Navigation Tab Button
Top-level nav: `#0d0d0e` bg, `#9ea0a9` text, radius 8px, padding 12px 16px.

### Flat Interactive Input
Search/form: transparent bg, `#ffffff` text + 1px border, radius 0px.

### Primary Action Button
High-priority CTA: `#e32652` bg, `#ffffff` text, radius 8px, padding 12px 16px.

### Secondary Action Button
`#222326` bg, `#ffffff` text, radius 0px, padding 12px 16px.

### Content Feature Card
Featured content: `#272c33` bg, radius 8px, padding 48px 96px.

### Visual Content Card
Showcase (instructor/class): `#222326` bg, radius 12px, no padding.

### Informational Badge
Labels: `#ffffff` bg, `#000000` text, radius 20px, padding 4px 12px.

## Do's and Don'ts

### Do
- Prioritize Pitch Black (#000000) and Charcoal Canvas (#222326) for primary surfaces.
- Use Action Raspberry (#e32652) exclusively for primary CTAs.
- Interactive elements: min 4px radius, important buttons 8px.
- Sohne Schmal 64-80px for monumental headings with tight letter-spacing.
- Inputs: 1px #ffffff border, 0px radius for stark integrated look.
- Use inset shadow `rgb(148, 154, 164) 0px 0px 0px 2px inset` for active states.

### Don't
- Light backgrounds for core content (strictly dark-mode dominant).
- Multiple chromatic colors close together — keep Action Raspberry as primary accent.
- Generic system fonts; always Sohne / Sohne Schmal / Ivar.
- Deviate from 4px spacing scale.
- Standard button shadows — rely on inset borders.
- Highly rounded corners (>20px) on cards or primary buttons.

## Surfaces

| Level | Name | Value | Purpose |
|-------|------|-------|---------|
| 0 | Pitch Black Base | `#000000` | Footer and full-width sections |
| 1 | Charcoal Canvas | `#222326` | Primary page bg, default card bg |
| 2 | Deep Slate Cards | `#272c33` | Elevated card surfaces |
| 3 | Graphite Modules | `#0d0d0e` | Navigation tabs, interactive modules |

## Elevation
- **Input focus:** `inset 0 0 0 2px #949aa4`
- **Hovered button/link:** `inset 0 0 0 2px #43454c`

## Imagery

Dominated by dramatic celebrity instructor portraits — tightly cropped, full-bleed or in cards with 8-12px radii. Iconography minimal (outlined/filled in Pure White or Silver Mist, occasional Lime/Raspberry pops). Strong instructor-first product showcase.

## Layout

Max-width contained, centered, with dynamic full-bleed hero sections. Hero: dark bg + prominent left-aligned headline + high-impact instructor imagery on right. Alternating black/charcoal sections with 64px vertical gaps. Text-left/image-right patterns, centered feature stacks, horizontal scrolling carousels. Sticky dark navigation with minimal text links and prominent Raspberry CTA.

## Agent Prompt Guide

Quick Color Reference:
- text: `#ffffff`
- background: `#222326`
- border: `#ffffff`
- accent: `#e32652`
- primary action: `#e32652` (filled)

Example Component Prompts:
1. Primary Action Button: `#e32652` bg, `#ffffff` text, 8px radius, 12px 16px padding.
2. Browsing filter button: Graphite Base bg, Sohne 14px weight 600, `#9ea0a9` text, letter-spacing 0.02em, radius 8px, padding 12px 16px.
3. Content card: Deep Slate bg, radius 8px. Inside: instructor image (radius 12px) + headline 'PHOTOGRAPHY' in Sohne 24px weight 600 `#ffffff` line-height 1.33. Card padding 48px.

## Similar Brands

- **Netflix** — Dark UI with cinematic content thumbnails.
- **Skillshare** — Expert-driven learning, clean dark presentation.
- **Apple TV+** — Premium dark interface, content-first.
- **Spotify** — Dark theme with bright accent colors.

## Quick Start

### CSS Custom Properties

```css
:root {
  /* Colors */
  --color-pitch-black: #000000;
  --color-charcoal-canvas: #222326;
  --color-graphite-base: #0d0d0e;
  --color-deep-slate: #272c33;
  --color-subtle-ash: #191c21;
  --color-iron-gray: #43454c;
  --color-silver-mist: #9ea0a9;
  --color-light-steel: #d4d5d9;
  --color-pure-white: #ffffff;
  --color-ghostly-gray: #f4f4f5;
  --color-action-raspberry: #e32652;
  --color-interactive-lime: #dcff00;
  --color-highlight-gold: #eed37f;
  --color-subtle-cadet: #596170;

  /* Typography — Families */
  --font-sohne: 'Sohne', ui-sans-serif, system-ui, sans-serif;
  --font-sohne-schmal: 'Sohne Schmal', ui-sans-serif, system-ui, sans-serif;
  --font-ivar-display-condensed: 'Ivar Display Condensed', ui-serif, serif;

  /* Typography — Scale */
  --text-caption: 12px;        --leading-caption: 1.45;
  --text-body-sm: 14px;        --leading-body-sm: 1.45;
  --text-body: 16px;           --leading-body: 1.45;
  --text-body-lg: 20px;        --leading-body-lg: 1.45;
  --text-heading-sm: 22px;     --leading-heading-sm: 1.45;
  --text-heading: 24px;        --leading-heading: 1.3;
  --text-heading-lg: 32px;     --leading-heading-lg: 1.3;
  --text-display-sm: 48px;     --leading-display-sm: 1.3;

  /* Spacing */
  --spacing-unit: 4px;
  --spacing-4: 4px;    --spacing-8: 8px;     --spacing-12: 12px;
  --spacing-16: 16px;  --spacing-20: 20px;   --spacing-24: 24px;
  --spacing-32: 32px;  --spacing-48: 48px;   --spacing-64: 64px;
  --spacing-80: 80px;  --spacing-96: 96px;   --spacing-112: 112px;

  /* Layout */
  --section-gap: 64px;
  --card-padding: 48px;
  --element-gap: 16px;

  /* Border Radius */
  --radius-cards: 8px;
  --radius-links: 8px;
  --radius-badges: 20px;
  --radius-images: 4px;
  --radius-inputs: 0px;
  --radius-buttons: 8px;

  /* Shadows */
  --shadow-subtle: rgb(148, 154, 164) 0px 0px 0px 2px inset;
  --shadow-subtle-2: rgb(39, 44, 51) 0px 0px 0px 1px inset;
}
```
