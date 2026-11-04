# Scene-adaptive liquid glass material

## User scope and authorization
New Trellis task. Implement surface-only liquid glass within the existing design system, automatically commit/push and use OrbStack for runtime/testing. Preserve existing surface markup, geometry, typography, icons, business copy and semantic focus/error/disabled states. New theme/tint controls are explicitly requested and reuse the existing Surface dialog; do not introduce a new visual surface component or wallpaper.

## Existing inventory and decisions
The app currently has a flat canvas, no wallpaper/media backdrop and no app-wide theme switch. Product tokens are in ProductUI.css; xterm's private theme is unrelated.
- Existing cards/panels (.card, inst-card, emp-card, job-card, hook-card), forms, search inputs, sidebar/header and full-page Wiki/E2E readers: stay solid over the current flat backdrop.
- Existing native dialog/drawer boxes, popconfirm, toast, tooltip: may opt into CSS glass over page content. Animated/transformed/composited surfaces never receive the SVG lens. Plain or indistinguishable backdrop instances remain solid.
- A static existing card over an actual readable image may opt in with the same tokens. This path receives a cached rim-only SVG lens and a geometry-reduced RGB split on supported Chromium; no image/wallpaper is added to the product.
- Unreadable content/video/cross-origin scenes use theme-selected ink and dense CSS-only tint, without fabricated pixel measurements.

## Acceptance
Shared registered material tokens, no per-component style recipes; 5x3 trimmed sampling, row-shared hysteretic ink, density/chroma/saturation, contrast protection and persistent last ink. Separate light/dark tint scales 0..1.5, system/manual theme, reduced transparency/forced-color solid fallbacks. Chromium-only decoded rim maps with matching convex superellipse corners, unique per-surface SVG filters, cached 9-slice maps reused on size changes, lens suppression for unsafe/animated layers. Test actual production browser rendering with readable/busy/dark/image and fallback scenes, preserve layout/fonts/radii/content, and rerun existing floating UI scenarios. Explicitly document which current product surfaces can actually use refraction.

## Research
See research/material.md. Numeric material examples are starting values; preserve fixed ink opacity, raise material density for contrast, and use a conservative solid fallback when a scene cannot be made readable. No promise of pixel access to arbitrary composited DOM.
