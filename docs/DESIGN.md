# mebit design

This is the mebit brand standard. Everything you need is written out here.

## How to use this file

The design lives in Penpot file `mebit`, on two pages.

- **`Organize`** has the app's screens.
- **`Organize · Design System`** has the colors, type, geometry and components the screens are built from.

1. **The screen is already drawn on `Organize`.** Match its board exactly.
2. **You are designing a new screen, or building something `Organize` doesn't draw.** Follow this file and `Organize · Design System`. Build from the library's components (Assets, then the local library, then `Organize`) and change their text as instance overrides.

If this file and the Penpot pages ever disagree, Penpot wins.

## Colors

### Brand palette

| Name | HEX | CMYK (print) | Use |
|---|---|---|---|
| Orbit Navy | `#002B49` | C100 M80 Y42 K45 | Headings, primary buttons, balance and transaction panels, selected states |
| Mercury Gold | `#FFBD59` | C6 M30 Y95 K0 | Accent. Fiat values on navy, highlights, the active navigation circle |
| Slate | `#394E6C` | none given | Data, labels, metadata |
| Soft Grey | `#EBEBEE` | none given | Page background, dividers, muted surfaces |
| Charcoal | `#4D4D4D` | none given | Secondary text |

### Supporting colors

| Name | HEX | Use |
|---|---|---|
| Card | `#FFFFFF` | Cards, fields, text on navy |
| Amber ink | `#C2700F` | Accent text on light surfaces, warnings |
| Amber soft | `#FDEFDD` | Warning backgrounds, icon tiles |
| Amber subtle | `#FFF8EF` | Selected card background |
| Border strong | `#C9CFD8` | Secondary outlines |
| Success | `#1B7A4B` | Verified status, success |
| Success soft | `#E4F2EA` | Verified badges |
| Black | `#000000` | Device chrome, shadow base |

### How to use color

- Navy carries trust, gold signals progress, and white keeps things approachable.
- Most screens are Soft Grey pages with white cards and navy text, plus one navy panel for weight.
- Gold is an accent, never a base color. Use it for one or two elements per screen.

### Contrast

| Pair | Ratio | Verdict |
|---|---|---|
| White on Orbit Navy | 14.57 : 1 | Pass |
| Mercury Gold on Orbit Navy | 8.79 : 1 | Pass |
| Mercury Gold on white | 1.66 : 1 | Never use |

- The target is WCAG AA, at least 4.5:1 for normal text and 3:1 for large text.
- On white or Soft Grey, set text in navy or Charcoal.
- Gold text works only on navy. On light surfaces, use Amber ink for gold-toned text.

## Typography

### Typefaces

| Role | Typeface |
|---|---|
| Thai text | Noto Sans Thai |
| Latin text and all numerals | Montserrat in Regular, Medium, SemiBold and Bold. Inter may stand in for it. |

The logo is custom lettering and is not set in either typeface.

### Text styles

| Style | Size | Weight | Line height | Use |
|---|---|---|---|---|
| Display / Amount | 44 px | 700 | 1.4 | Amount being entered or sent |
| Display / Balance | 38 px | 700 | 1.4 | Wallet balance |
| Heading / Screen | 28 px | 500 | 1.18 | Screen title |
| Heading / Card | 15.5 px | 600 | 1.35 | Card title |
| Label / Action | 14.5 px | 600 | 1.4 | Button labels |
| Body / Default | 13.5 px | 300 | 1.45 | Body text |
| Body / Emphasis | 13 px | 600 | 1.4 | Emphasized body text |
| Label / Status | 11 px | 600 | 1.4 | Status badges |
| Label / Eyebrow | 10.5 px | 600 | 1.35 | Small capitals label above a title |
| Caption / Metadata | 10.5 px | 300 | 1.35 | Timestamps, secondary detail |

For print and marketing, the scale is Display 46–60 pt, Section 28–36 pt, Body 14–18 pt and Label 10–12 pt.

### Numbers

- Use tabular figures (every digit the same width), so amounts line up.
- Always state the unit, as in `0.0050 BTC`.
- Round consistently.
- Never imply that value is certain to go up.

## Layout and spacing

| Rule | Value |
|---|---|
| Mobile canvas | 402 × 874 |
| Screen gutter | 20 px on each side |
| Content width | 362 px |
| Spacing scale | 4, 8, 12, 16, 20, 24, 32 px |

- **Grid.** 12 columns on an 8 pt baseline. In print, the minimum margin is 0.65 in.
- **Align.** Keep text and blocks on consistent vertical lines.
- **Space.** Prefer white space to decoration.
- **Hierarchy.** Keep the amount, the currency and the action buttons visually separate.

## Radii

Keep corners simply rounded and purposeful.

| Element | Radius |
|---|---|
| Field | 18 px |
| Card | 20 px |
| Panel | 22 px |
| Hero | 24 px |
| Checkbox | 7 px |
| Buttons, badges | Pill (999 px) |

Cards on light surfaces have a 1 px border.

## Components

All of these are on `Organize · Design System` and in the local library under `Organize`. Use an instance instead of drawing one.

### Buttons and actions

| Component | Size | Look |
|---|---|---|
| Primary button | 362 × 54 | Navy fill, white label, pill |
| Secondary button | 362 × 52 | White fill, navy label, pill |
| Disabled button | Same as primary | Navy at 25% opacity |
| Back action | 36 px | White |
| Paste action | 30 px | Soft Grey |
| Sign action | 34 px | Navy |

### Other controls and patterns

- **Status badges.** 24 px high in three tones: verified, pending and neutral.
- **Choice buttons and policy presets.** A navy fill with white text marks the selected choice. A light surface marks one that's available.
- **Fee option and selected key.** Selected means a 2 px amber border on an Amber subtle surface.
- **Checkbox.** 22 × 22 px, 7 px corners.
- **Key card.** 362 × 62. An identity tile, the key name, the device and fingerprint, and a verification badge.
- **Transaction panels** (balance, send summary). A navy surface with a white amount and the fiat value in gold.
- **Notices.** Network guidance uses an Amber soft surface with Amber ink text. State explanations use a neutral surface.
- **Fields.** Destination is 362 × 76 and Amount is 362 × 168.

### Bottom navigation

- **Size.** A 402 × 130 full-width dock with three tabs, กระเป๋า · รับ / ส่ง · กุญแจ.
- **Bitcoin action.** A 52 px Bitcoin button sits in a curved recess in the middle. It opens the Receive / Send chooser.
- **Active tab.** A 32 px soft yellow circle.
- **Placement.** On a 402 × 874 screen, place the dock at x 0, y 744. Keep transaction content above y 734.

## Brand graphic elements

These come from the logo's curves, thin lines and purposeful white space. Use them for structure, not decoration.

- **Orbit.** Thin concentric rings around the symbol, gold or low-opacity white on navy. Use the orbit for hero areas, and never let it compete with the content.
- **Thin accent line.** A thin horizontal gold rule that separates a heading from its content.
- **Left anchor.** A short vertical gold bar at the left edge of a block, marking it as key.

### Imagery

- **Human.** People learning, deciding and using technology with confidence.
- **System.** Clean devices, hands on interfaces, calm real settings.
- **Detail.** Materials, light and deliberate timing.

Never use gold bars, showers of coins, trader clichés, or imagery that promises wealth.

## Logo

Always place the logo from its file. Never draw, trace or rebuild it.

### Versions

| Version | Use |
|---|---|
| Primary | Light backgrounds |
| Dark background | Navy or dark backgrounds |
| Single color | Print |

### Rules

- **Clear space.** Keep a margin of at least 10% of the symbol's width on all four sides, with no text, border or other mark inside it.
- **Minimum size.** The full stacked mark must be at least 240 px wide (45 mm in print). The symbol alone must be at least 40 px (12 mm in print).
- **Symbol alone.** Use the symbol alone wherever the descriptor would be too small to read, such as an app icon, a favicon, an avatar or a small header.
- **Don't.** Never stretch, squash or rotate it. Never recolor it, add outlines, shadows or glows, or set it on a busy background.

## Voice

The brand voice is confident but not overconfident, and helpful but not salesy.

| Principle | Thai | Meaning |
|---|---|---|
| Clear | ชัดเจน | Use everyday words before Bitcoin jargon. Explain every unfamiliar term at least once. |
| Calm | สุขุม | Be direct and steady, and specific when markets are volatile. |
| Respectful | ให้เกียรติ | Treat users as people who can make their own decisions. |
| Transparent | โปร่งใส | State fees, limits, conditions and risks. |

### Do and don't

| Don't write | Write instead |
|---|---|
| รับผลกำไรอย่างปลอดภัยและง่ายดาย (safe, easy profit) | เรียนรู้บิตคอยน์และความเสี่ยง ก่อนตัดสินใจด้วยตนเอง |
| คลิกเดียว ราคาพุ่งทะยาน (one click, price soars) | ตรวจสอบจำนวนเงินและค่าธรรมเนียมก่อนยืนยันรายการ |
| เงินของคุณปลอดภัย 100% (your money is 100% safe) | เรียนรู้การควบคุมกุญแจและระบบป้องกันบัญชีว่าทำงานอย่างไร |

- Describe actions and evidence instead of piling on adjectives.
- Show fees and transaction details before the user confirms.
- Describe ownership and control accurately. When you explain keys, never promise protection that cannot be delivered.
- Never guarantee returns or principal, and never present Bitcoin as a bank deposit.
- Never show unconfirmed prices, return claims, or limited-time urgency.
- Never describe a feature as live before it ships.

### Thai and English

- Thai and English copy must say the same thing. Write the Thai copy first, for clarity.
- `Bitcoin Saving Company` is part of the logo. Never translate it in a way that suggests a protected bank deposit.
