// One invisible SVG per surface; decoded raster patches are shared. Resizing
// moves/stretches the nine patches rather than regenerating a full-size bitmap.
const NS = 'http://www.w3.org/2000/svg'
const cache = new Map()
let sequence = 0
const node = (tag, attributes = {}) => {
  const element = document.createElementNS(NS, tag)
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value)
  return element
}
export function supportsLens() {
  return /Chrome|Chromium|Edg\//.test(navigator.userAgent) && !/Firefox|FxiOS|CriOS/.test(navigator.userAgent) && CSS.supports('backdrop-filter', 'url(#glass-support)')
}
function geometry(element) {
  const css = getComputedStyle(element), rect = element.getBoundingClientRect()
  if (Math.min(rect.width, rect.height) < 32 || css.willChange !== 'auto' || css.transform !== 'none' || css.filter !== 'none' || css.animationName !== 'none' || css.perspective !== 'none' || Number(css.opacity) < 1 || css.isolation === 'isolate' || /paint|strict|content/.test(css.contain) || element.closest('[role=menu]')) return null
  const values = ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight'].map(corner => css[`border${corner}Radius`])
  if (new Set(values).size !== 1 || values.some(v => /%/.test(v))) return null
  const radii = values[0].split(' ').map(parseFloat)
  if (radii.length > 1 && radii[0] !== radii[1]) return null
  const radius = Math.min(radii[0], rect.width / 2, rect.height / 2)
  const shapes = ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight'].map(corner => css[`corner${corner}Shape`] || 'round')
  if (new Set(shapes).size !== 1) return null
  const shape = shapes[0], k = ({ round: 1, squircle: 2, bevel: 0, square: Infinity })[shape] ?? Number(/^superellipse\(([^)]+)\)$/.exec(shape)?.[1])
  if (!Number.isFinite(k) || k < 0 || k > 5) return null // Concave/unknown geometry: keep CSS glass.
  const rim = Math.max(4, Math.min(44, Math.min(rect.width, rect.height) / 3.5))
  const band = Math.ceil(radius + rim)
  if (band * 2 >= Math.min(rect.width, rect.height)) return null
  return { width: rect.width, height: rect.height, radius, rim, band, exponent: 2 ** k }
}
function distanceToSegment(x, y, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1]
  const t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy || 1)))
  const px = a[0] + dx * t, py = a[1] + dy * t
  return { distance: Math.hypot(x - px, y - py), dx: x - px, dy: y - py }
}
async function patches(g) {
  const key = [g.radius, g.rim, g.exponent].join(':')
  if (cache.has(key)) return cache.get(key)
  const pending = (async () => {
    const { radius: r, rim, band: n, exponent: p } = g, max = 1.1 * rim
    const curve = [[0, n]]
    // Uniform coordinates on both axes keep square-like superellipses accurate.
    const arc = []
    for (let i = 0; i <= 96; i++) {
      const v = i / 96
      arc.push([r * (1 - v), r * (1 - (1 - v ** p) ** (1 / p))])
      arc.push([r * (1 - (1 - v ** p) ** (1 / p)), r * (1 - v)])
    }
    arc.sort((a, b) => a[0] - b[0]); curve.push(...arc, [n, 0])
    const encoded = []
    for (let part = 0; part < 8; part++) {
      const corner = part < 4, w = corner ? n : part < 6 ? 1 : n, h = corner ? n : part < 6 ? n : 1
      const canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h
      const ctx = canvas.getContext('2d'), pixels = ctx.createImageData(w, h)
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const mx = corner && part % 2 ? n - x - .5 : x + .5
        const my = corner && part >= 2 ? n - y - .5 : y + .5
        let dx = 0, dy = 0
        if (corner) {
          const outside = mx < r && my < r && ((r - mx) / r) ** p + ((r - my) / r) ** p > 1
          if (!outside) {
            let nearest = { distance: Infinity }
            for (let i = 1; i < curve.length; i++) {
              const candidate = distanceToSegment(mx, my, curve[i - 1], curve[i])
              if (candidate.distance < nearest.distance) nearest = candidate
            }
            if (nearest.distance > 0 && nearest.distance < rim) {
              const amount = max * (1 - nearest.distance / rim) ** 2.6 / nearest.distance
              dx = nearest.dx * amount * (part % 2 ? -1 : 1)
              dy = nearest.dy * amount * (part >= 2 ? -1 : 1)
            }
          }
        } else {
          const d = part === 4 ? y + .5 : part === 5 ? n - y - .5 : part === 6 ? x + .5 : n - x - .5
          const amount = d < rim ? max * (1 - d / rim) ** 2.6 : 0
          if (part < 6) dy = amount * (part === 4 ? 1 : -1)
          else dx = amount * (part === 6 ? 1 : -1)
        }
        const at = (y * w + x) * 4
        pixels.data.set([Math.round(128 + dx / (2 * max) * 255), Math.round(128 + dy / (2 * max) * 255), 128, 255], at)
      }
      ctx.putImageData(pixels, 0, 0)
      const url = canvas.toDataURL(), image = new Image(); image.src = url
      await image.decode() // Never publish a filter whose feImage isn't decoded.
      encoded.push(url)
    }
    return encoded
  })()
  cache.set(key, pending)
  if (cache.size > 48) cache.delete(cache.keys().next().value)
  return pending
}
export async function createLens(element) {
  if (!supportsLens()) return null
  const g = geometry(element)
  if (!g) return null
  const urls = await patches(g)
  if (!element.isConnected) return null
  const id = `glass-rim-${++sequence}`, svg = node('svg', { 'aria-hidden': 'true', width: 0, height: 0, focusable: 'false' })
  svg.style.cssText = 'position:fixed;pointer-events:none;width:0;height:0;overflow:hidden'
  const filter = node('filter', { id, filterUnits: 'userSpaceOnUse', primitiveUnits: 'userSpaceOnUse', x: 0, y: 0, 'color-interpolation-filters': 'sRGB' })
  svg.append(filter)
  filter.append(node('feFlood', { 'flood-color': 'rgb(128 128 128)', result: 'neutral' }))
  const pieces = urls.map((url, i) => node('feImage', { href: url, preserveAspectRatio: 'none', result: `patch${i}` }))
  filter.append(...pieces)
  const merge = node('feMerge', { result: 'rimMap' })
  merge.append(node('feMergeNode', { in: 'neutral' }), ...pieces.map((_, i) => node('feMergeNode', { in: `patch${i}` })))
  filter.append(merge)
  // Compensate 8-bit neutral 128 to SVG's exact .5, keeping the center flat.
  const balance = node('feComponentTransfer', { in: 'rimMap', result: 'balancedMap' })
  balance.append(node('feFuncR', { type: 'linear', slope: 1, intercept: -.5 / 255 }), node('feFuncG', { type: 'linear', slope: 1, intercept: -.5 / 255 }))
  filter.append(balance)
  const split = .15 * Math.min(1, g.radius / g.rim)
  for (const [index, channel] of ['red', 'green', 'blue'].entries()) {
    filter.append(node('feDisplacementMap', { in: 'SourceGraphic', in2: 'balancedMap', scale: 2 * 1.1 * g.rim * [1 + split, 1, 1 - split][index], xChannelSelector: 'R', yChannelSelector: 'G', result: `${channel}Bend` }))
    const matrix = Array(20).fill(0); matrix[index * 5 + index] = 1; matrix[18] = 1
    filter.append(node('feColorMatrix', { in: `${channel}Bend`, type: 'matrix', values: matrix.join(' '), result: channel }))
  }
  filter.append(node('feBlend', { in: 'red', in2: 'green', mode: 'screen', result: 'redGreen' }), node('feBlend', { in: 'redGreen', in2: 'blue', mode: 'screen' }))
  const update = () => {
    const now = geometry(element)
    if (!now || now.radius !== g.radius || now.rim !== g.rim || now.exponent !== g.exponent) return false
    const { width: w, height: h, band: n } = now
    filter.setAttribute('width', w); filter.setAttribute('height', h)
    const bounds = [[0, 0, n, n], [w - n, 0, n, n], [0, h - n, n, n], [w - n, h - n, n, n], [n, 0, w - 2 * n, n], [n, h - n, w - 2 * n, n], [0, n, n, h - 2 * n], [w - n, n, n, h - 2 * n]]
    pieces.forEach((piece, i) => ['x', 'y', 'width', 'height'].forEach((name, j) => piece.setAttribute(name, bounds[i][j])))
    return true
  }
  update()
  // A native dialog's top layer must own its filter, just like its tooltips.
  ;(element.closest('dialog[open]') || document.body).append(svg)
  element.style.setProperty('--glass-lens', `url(#${id})`)
  return { update, destroy() { element.style.removeProperty('--glass-lens'); svg.remove() } }
}
