// All scene values are normalized to [0, 1]. No business content is persisted.
export const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value))
export const luminance = ([r, g, b]) => (.299 * r + .587 * g + .114 * b) / 255
export function summarize(points) {
  const sorted = [...points].sort((a, b) => luminance(a) - luminance(b))
  const trimmed = sorted.slice(2, -2)
  const rgb = [0, 1, 2].map(i => trimmed.reduce((sum, p) => sum + p[i], 0) / trimmed.length)
  return { rgb, luminance: luminance(rgb), texture: luminance(sorted.at(-1)) - luminance(sorted[0]), chroma: (Math.max(...rgb) - Math.min(...rgb)) / 255 }
}
export function chooseInk(theme, light, previous) {
  if (theme === 'dark') return 'light'
  if (previous === 'light' && light < .55) return previous
  if (previous === 'dark' && light > .45) return previous
  return light < .5 ? 'light' : 'dark'
}
export function contrast(a, b) {
  const linear = rgb => rgb.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
  const lum = rgb => { const [r, g, b] = linear(rgb); return .2126 * r + .7152 * g + .0722 * b }
  const x = lum(a), y = lum(b)
  return (Math.max(x, y) + .05) / (Math.min(x, y) + .05)
}
const composite = (a, b, alpha) => a.map((v, i) => v * (1 - alpha) + b[i] * alpha)
export function material(theme, ink, scene, scale = 1, points = []) {
  const dark = theme === 'dark', lightInk = ink === 'light'
  const tint = dark ? [20, 20, 24] : lightInk ? [16, 18, 22] : [255, 255, 255]
  const density = scene ? clamp(.55 * (1 - Math.abs(scene.luminance - (lightInk ? 1 : 0))) + .7 * scene.texture + .3 * scene.chroma) : 1
  const range = dark ? [.16, .38] : lightInk ? [.1, .3] : [.06, .22]
  let alpha = range[0] + (range[1] - range[0]) * density
  const keep = scene ? Math.max(.45, 1 - .75 * scene.chroma) : 1
  const saturation = 1 + ((dark ? 1.6 : lightInk ? 1.7 : 1.8) * keep - 1) * Math.min(scale, 1)
  const brightness = 1 + ((dark ? .8 : lightInk ? .85 : 1.06) - 1) * Math.min(scale, 1)
  // Fixed secondary ink: 58/58/64 at .70 calibrates the reference 60/60/67
  // (4.47:1 even over white) to AA. Opacity never varies with the scene.
  const secondary = lightInk ? [235, 235, 245] : [58, 58, 64]
  const opacity = lightInk ? .64 : .7
  const filtered = (points.length ? points : [[0, 0, 0], [255, 255, 255]]).map(p => {
    const gray = .2126 * p[0] + .7152 * p[1] + .0722 * p[2]
    return p.map(v => clamp((gray + (v - gray) * saturation) * brightness, 0, 255))
  })
  const passes = a => filtered.every(p => {
    let background = composite(p, tint, a)
    // Top-left inner light is the worst case for light ink. Include it.
    if (lightInk) background = composite(background, [255, 255, 255], dark ? .035 : .06)
    // Direct text nodes cannot blend independently without changing markup.
    // Test normal compositing too; leaf multiply/plus-lighter only improves it.
    const blended = composite(background, secondary, opacity)
    return contrast(blended, background) >= 4.5
  })
  // Conservative contrast floor also handles unknown DOM/video/CORS backdrops.
  // Density may exceed the reference range: accessibility takes precedence.
  while (alpha < 1 && !passes(alpha)) alpha = Math.min(1, alpha + .02)
  return { tint, alpha: clamp(alpha * scale), saturation, brightness, density, ink, dark }
}
