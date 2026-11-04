// Canvas reads image resources only. Arbitrary DOM and live video cannot be read
// reliably; callers must use the dense theme fallback rather than invent pixels.
import { summarize } from './material'
const images = new Map()
function loadImage(url) {
  if (!images.has(url)) {
    const pending = new Promise((resolve, reject) => {
      const image = new Image()
      image.crossOrigin = 'anonymous'
      image.onload = () => image.decode().then(() => resolve(image), reject)
      image.onerror = reject
      image.src = url
    }).catch(() => null)
    images.set(url, pending)
    if (images.size > 32) images.delete(images.keys().next().value)
  }
  return images.get(url)
}
function position(value, free) {
  if (value.endsWith('%')) return free * parseFloat(value) / 100
  if (value === 'center') return free / 2
  if (value === 'right' || value === 'bottom') return free
  return parseFloat(value) || 0
}
export async function sampleScene(surface) {
  let host = surface.parentElement
  while (host && host !== document.documentElement) {
    const css = getComputedStyle(host)
    const image = /^url\(["']?(.*?)["']?\)$/.exec(css.backgroundImage)
    const img = [...host.children].find(child => child.tagName === 'IMG')
    const unreadable = () => ({ unreadable: true, host })
    if (host.querySelector(':scope > video')) return unreadable()
    if (image || img) {
      if ((!img && css.backgroundAttachment === 'fixed') || css.transform !== 'none') return unreadable()
      const source = img || await loadImage(image[1])
      if (!source || !source.complete || !source.naturalWidth) return unreadable()
      const rect = (img || host).getBoundingClientRect(), box = surface.getBoundingClientRect()
      const nw = source.naturalWidth, nh = source.naturalHeight
      const size = img ? getComputedStyle(img).objectFit : css.backgroundSize
      let width = nw, height = nh
      if (size === 'cover' || size === 'contain') {
        const ratio = Math[size === 'cover' ? 'max' : 'min'](rect.width / nw, rect.height / nh)
        width = nw * ratio; height = nh * ratio
      } else if ((img && size === 'fill') || size === '100% 100%') { width = rect.width; height = rect.height }
      else if (size !== 'auto' && size !== 'auto auto' && !(img && size === 'none')) return unreadable() // Unsupported crop: dense fallback.
      const [px = '50%', py = '50%'] = (img ? getComputedStyle(img).objectPosition : css.backgroundPosition).split(' ')
      const left = rect.left + position(px, rect.width - width), top = rect.top + position(py, rect.height - height)
      const canvas = document.createElement('canvas'); canvas.width = 5; canvas.height = 3
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      try {
        for (let y = 0; y < 3; y++) for (let x = 0; x < 5; x++) {
          const sx = (box.left + box.width * (x + .5) / 5 - left) / width * nw
          const sy = (box.top + box.height * (y + .5) / 3 - top) / height * nh
          if (sx < 0 || sy < 0 || sx >= nw || sy >= nh) return unreadable()
          ctx.drawImage(source, sx, sy, 1, 1, x, y, 1, 1)
        }
        const pixels = ctx.getImageData(0, 0, 5, 3).data, points = []
        for (let i = 0; i < pixels.length; i += 4) {
          if (pixels[i + 3] !== 255) return unreadable()
          points.push([...pixels.slice(i, i + 3)])
        }
        return { ...summarize(points), points, host }
      } catch { return unreadable() }
    }
    if (css.backgroundImage !== 'none') return unreadable()
    const bg = css.backgroundColor
    if (bg && bg !== 'transparent' && !/rgba?\([^)]*(?:,\s*0|\/\s*0)\s*\)$/.test(bg) && !bg.endsWith(', 0)')) return null
    host = host.parentElement
  }
  return null
}
