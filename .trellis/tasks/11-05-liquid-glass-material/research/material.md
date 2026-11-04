# Primary references

- MDN backdrop-filter: https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/backdrop-filter — filters affect pixels behind a translucent surface up to a backdrop root; URL support must be gated beyond parsing.
- MDN superellipse(): https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/superellipse — CSS exponent is 2^K; round K=1, squircle K=2. Never substitute a circle for a squircle. Unsupported concave/custom shapes fall back to CSS material rather than using a mismatched lens.
- MDN media queries: https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media — reduced-transparency and forced-colors are preference fallbacks.

Current sources inspected: App.jsx, ProductUI.css/App.css, existing FloatingUI/Toast, all image/background/theme references. No image/video/wallpaper surfaces or global dark-mode settings exist. Real page-overlay backdrops are complex DOM, so theme/dense fallback is honest; readable image sampling and refraction are exercised on opt-in existing surfaces in browser tests.

Implementation direction: shared material CSS + nonvisual controller and lens helper; reuse Surface for settings. Nine-slice cached vector maps prevent full-map rasterization on every resize. DOM scene sampling is conservative; do not claim screenshots or cross-origin pixels can be read with Canvas.
