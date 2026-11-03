---
name: Transcriptor
description: A quiet native workspace for recordings and readable transcripts.
colors:
  primary: "#006B58"
  on-primary: "#FFFFFF"
  paper: "#FAFBF9"
  ink: "#19221E"
  secondary-ink: "#414943"
  surface-low: "#F0F4EF"
  divider: "#C1C9C1"
  dark-paper: "#101613"
  dark-ink: "#E0E5E0"
  dark-primary: "#8ED8C4"
typography:
  headline:
    fontFamily: Android system sans with platform multilingual fallback
    fontSize: 32sp
    fontWeight: 600
    lineHeight: 38sp
    letterSpacing: -0.6sp
  title:
    fontFamily: Android system sans
    fontSize: 22sp
    fontWeight: 600
    lineHeight: 28sp
    letterSpacing: -0.2sp
  body:
    fontFamily: Android system sans
    fontSize: 17sp
    fontWeight: 400
    lineHeight: 28sp
    letterSpacing: 0sp
  supporting:
    fontFamily: Android system sans
    fontSize: 15sp
    fontWeight: 400
    lineHeight: 23sp
  label:
    fontFamily: Android system sans
    fontSize: 14sp
    fontWeight: 600
    lineHeight: 20sp
rounded:
  small: 10dp
  medium: 14dp
  large: 16dp
spacing:
  related: 8dp
  group: 16dp
  gutter: 24dp
  section: 24dp
components:
  import-action:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.medium}"
    padding: 16dp
    height: min 56dp
  settings-group:
    backgroundColor: "{colors.surface-low}"
    textColor: "{colors.ink}"
    rounded: "{rounded.large}"
  project-row:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    padding: 12dp 0dp
---

# Design System: Transcriptor

## Overview

**Creative North Star: "The Quiet Document Workspace"**

Transcriptor feels like a well arranged document library. Recordings, models, and settings have distinct destinations; transcript text receives the most generous space. Native Material 3 controls provide predictable interaction, while measured typography and restrained green accents give the app its identity.

**Key Characteristics:**
- Clear reading hierarchy with system text scaling and Arabic fallback.
- Flat lists, generous margins, and grouped settings with explicit labels.
- Persistent system, light, and dark appearance.

## Colors

The semantic Material scheme in `AppTheme.kt` is authoritative. Paper and ink carry content; green marks actions, selected destinations, and healthy process states. Failed operations use the Material error roles. Light and dark schemes have independent surface levels and foregrounds. Per-screen code uses semantic roles rather than literal color values.

**The Quiet Accent Rule.** Use accent for actions and state; keep long text on neutral surfaces.

## Typography

Use the Android system sans face and its full platform fallback chain. No downloaded font dependency is needed to read Arabic or mixed scripts. The Material type scale controls every screen and follows system font size.

Headline large introduces the recording library. Headline small introduces document and workflow sections. Title large introduces models and major groups; title medium introduces records and settings. Body large is the transcript reading voice. Body medium carries explanation; body small carries metadata. Labels stay at or above 12sp. Transcript paragraphs use the body large 28sp line height without additional multipliers.

**The Text First Rule.** A transcript is a document: preserve continuous selectable paragraphs and apply search highlights within the surrounding text.

## Layout

Compact devices use three Material navigation destinations: Recordings, Models, and Settings. A selected recording opens a focused detail screen with native Back. At 840dp total window width the destinations move to a navigation rail, with a 360dp recording pane alongside the detail area. Do not retain the phone bottom navigation on tablets.

Content uses 24dp gutters and 24dp section separation. Model and document content is centered and capped at 760dp; settings at 720dp. Project rows use 48dp file tiles, a title, compact metadata, real state, and a trailing chevron. They are divided rows rather than individual cards.

The import summary stays compact. **Atur / Configure** opens a native sheet for model, language, and speaker options, keeping saved projects in reach. Fixed-language models show only their supported language. All selectors and long forms scroll; controls respect system and IME insets.

## Elevation & Depth

Use Material tonal surfaces for grouped controls and import configuration. Library and model lists remain flat with thin semantic dividers. Material sheets and dialogs own their standard modal depth. No ornamental shadows, gradients, or decorative media.

## Shapes

Use the theme's 10dp / 14dp / 16dp corner scale. The primary import action uses medium corners and at least 56dp height. Native Material navigation indicators, segmented controls, and switches retain their component shapes. Every control has at least a 48dp touch target.

## Components

- **App shell:** a context title and native Back when a recording is open; top-level destinations live in the bottom bar or rail. Appearance moves into Settings.
- **Settings:** a full page with appearance controls, grouped language and performance rows, diagnostics, and creator information. Language and performance choices use focused native dialogs. Diagnostic sharing and clearing keep their established behavior and confirmations. Creator: tomz, GitHub tomzsh, qodequ.com.
- **Model library:** Transcription / AI translator / Speakers tabs, followed by a readable model catalog. Tab labels stay on one line and scroll horizontally when enlarged text needs more space. Labels, size, license, description, explicit installation/activation actions, download progress, and verification remain visible. Device acceleration expands on request. Changes and imports are locked during processing.
- **Transcript:** Transcript / Translation / Details tabs use the same scrollable native tab row, avoiding broken words at large text sizes. Playback is optional; Copy, Display, Search, and Edit form a compact wrapping tool group. Search opens a field on demand. Presentation settings persist per project and govern copy and document export. Timed subtitle export requirements remain explicit.
- **Editors:** cue boundaries and speaker data remain editable. Saves use the submitted snapshot, disable repeat submissions, and retain errors beside the action. Text controls remain reachable above the IME.
- **Progress:** show actual processed-audio or validated-cue percentages. Indeterminate work retains an activity indicator and shared elapsed stage timing. Restored work without a stage start labels the view-time fallback accurately. Notifications retain their own running chronometer.
- **Exports:** the result receiver remains at the app root, above scrolling and navigation. Freeze text and presentation before the external picker opens.

## Do's and Don'ts

### Do:
- Do use native Material components and predictable Back behavior.
- Do preserve multilingual, enlarged-text, light, and dark behavior.
- Do keep controls discoverable while giving transcript text room.
- Do use the bundled Lucide vectors with accessible names on standalone actions.

### Don't:
- Don't replace document structure with nested card stacks.
- Don't fabricate progress, model claims, or language specialization for generic models.
- Don't hide failed actions or discard cue timings, speakers, or original text.
