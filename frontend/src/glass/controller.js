import { chooseInk, material } from './material'
import { preferences } from './preferences'
import { sampleScene } from './scene'
import { createLens } from './lens'
const FLOATS = '.surface-host[open] > .modal-box, .popconfirm-box, .toast, .ui-tooltip'
const SELECTOR = `${FLOATS}, [data-glass]`
const set = (el, key, value) => { if (el.style.getPropertyValue(key) !== String(value)) el.style.setProperty(key, value) }
const mark = (el, key, value) => { if (el.getAttribute(key) !== value) el.setAttribute(key, value) }
function memoryKey(el) {
  // Structure only: no supplier names, text, API data or document contents.
  const path = []; let current = el
  while (current?.parentElement && path.length < 5) {
    path.push(`${current.tagName}:${[...current.parentElement.children].filter(child => child.tagName === current.tagName).indexOf(current)}`)
    current = current.parentElement
  }
  return `${location.pathname}:${el.getAttribute('data-glass-key') || path.join('/')}`
}
function previousInk(el) { try { return localStorage.getItem(`glass.ink.${memoryKey(el)}`) } catch { return null } }
function rememberInk(el, ink) { try { localStorage.setItem(`glass.ink.${memoryKey(el)}`, ink) } catch { /* Optional. */ } }
function markInk(el) {
  // Blend ink leaves, never the slab or a whole subtree: that would also blend
  // its background, semantic red failures, solid inputs and action buttons.
  for (const leaf of el.querySelectorAll('*')) {
    if (leaf.children.length || !leaf.textContent.trim() || leaf.closest('button,a,input,textarea,select,code,pre,[role="alert"],.confirmation-icon,.error,.success,.status-badge,.hook-badge')) continue
    if (leaf.style.color || leaf.closest('[style*="color:"]')) continue
    mark(leaf, 'data-glass-ink', 'true')
  }
}
export function startGlass() {
  const entries = new Map()
  const reduced = matchMedia('(prefers-reduced-transparency: reduce)'), forced = matchMedia('(forced-colors: active)')
  let disposed = false, frame = 0, generation = 0
  const observer = new ResizeObserver(() => schedule())
  function remove(el, entry) { entry.lens?.destroy(); observer.unobserve(el); el.removeAttribute('data-glass-active'); entries.delete(el) }
  async function refresh() {
    frame = 0
    const run = ++generation, theme = document.documentElement.dataset.theme || 'light'
    const found = new Set(document.querySelectorAll(SELECTOR))
    for (const [el, entry] of entries) if (!found.has(el) || !el.isConnected) remove(el, entry)
    const visible = [...found].filter(el => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden')
    const scenes = await Promise.all(visible.map(el => reduced.matches || forced.matches ? null : sampleScene(el)))
    if (disposed || run !== generation) return
    const groups = []
    visible.forEach((el, i) => {
      const unreadable = scenes[i]?.unreadable, scene = unreadable ? null : scenes[i], rect = el.getBoundingClientRect()
      let group = scene && groups.find(g => g.host === scene.host && Math.abs(g.top - rect.top) < 8 && g.parent === el.parentElement)
      if (!group) { group = { host: scene?.host, top: rect.top, parent: el.parentElement, items: [] }; groups.push(group) }
      group.items.push({ el, scene, unreadable })
    })
    for (const group of groups) {
      const readable = group.items.filter(item => item.scene)
      const light = readable.reduce((sum, item) => sum + item.scene.luminance, 0) / (readable.length || 1)
      const old = entries.get(group.items[0].el)?.ink || previousInk(group.items[0].el)
      const ink = readable.length ? chooseInk(theme, light, old) : theme === 'dark' ? 'light' : 'dark'
      for (const { el, scene, unreadable } of group.items) {
        // An explicit card over a flat background stays solid. Floating surfaces
        // over live DOM use conservative dense glass; their pixels aren't sampled.
        if (!scene && !unreadable && !el.matches(FLOATS) && !el.hasAttribute('data-glass-scene-unreadable')) {
          const entry = entries.get(el); if (entry) remove(el, entry)
          continue
        }
        let entry = entries.get(el)
        if (!entry) { entry = {}; entries.set(el, entry); observer.observe(el) }
        entry.ink = ink
        const values = material(theme, ink, scene, preferences[theme], scene?.points)
        mark(el, 'data-glass-active', ink)
        mark(el, 'data-glass-source', scene ? 'image' : 'unreadable')
        mark(el, 'data-glass-state', theme === 'dark' ? 'dark' : ink === 'light' ? 'dark-area' : 'light')
        set(el, '--glass-tint', `rgb(${values.tint.join(' ')})`)
        set(el, '--glass-alpha', values.alpha)
        set(el, '--glass-saturation', values.saturation)
        set(el, '--glass-brightness', values.brightness)
        set(el, '--glass-highlight', (values.dark ? .035 : ink === 'light' ? .06 : .3) * Math.min(preferences[theme], 1))
        rememberInk(el, ink); markInk(el)
        if (!scene || reduced.matches || forced.matches) { entry.lens?.destroy(); entry.lens = null }
        else if (entry.lens && !entry.lens.update()) { entry.lens.destroy(); entry.lens = null }
        if (scene && !reduced.matches && !forced.matches && !entry.lens && !entry.pending) {
          entry.pending = true
          createLens(el).then(lens => {
            entry.pending = false
            if (disposed || !entries.has(el) || el.dataset.glassSource !== 'image') lens?.destroy()
            else entry.lens = lens
          }).catch(() => { entry.pending = false }) // CSS glass remains on graphics failure.
        }
      }
    }
  }
  function schedule() { if (!disposed && !frame) frame = requestAnimationFrame(refresh) }
  const mutation = new MutationObserver(records => {
    if (records.some(record => record.type === 'childList' || ['class', 'style', 'open', 'src', 'data-theme', 'data-glass'].includes(record.attributeName))) schedule()
  })
  mutation.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'open', 'src', 'data-glass'] })
  const rootMutation = new MutationObserver(schedule)
  rootMutation.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  window.addEventListener('appearancechange', schedule)
  window.addEventListener('resize', schedule)
  document.addEventListener('scroll', schedule, true)
  document.addEventListener('load', schedule, true)
  document.addEventListener('transitionend', schedule, true)
  document.addEventListener('animationend', schedule, true)
  reduced.addEventListener('change', schedule); forced.addEventListener('change', schedule)
  schedule()
  return () => {
    disposed = true; generation++; cancelAnimationFrame(frame); mutation.disconnect(); rootMutation.disconnect(); observer.disconnect()
    for (const entry of entries.values()) entry.lens?.destroy()
    window.removeEventListener('appearancechange', schedule); window.removeEventListener('resize', schedule)
    document.removeEventListener('scroll', schedule, true); document.removeEventListener('load', schedule, true)
    document.removeEventListener('transitionend', schedule, true); document.removeEventListener('animationend', schedule, true)
    reduced.removeEventListener('change', schedule); forced.removeEventListener('change', schedule)
  }
}
