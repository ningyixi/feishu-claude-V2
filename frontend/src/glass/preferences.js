const KEY = 'workspace.appearance.v1'
const media = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null
let saved = {}
try { saved = JSON.parse(localStorage.getItem(KEY) || '{}') } catch { /* Private browsing / invalid old settings. */ }
if (!saved || typeof saved !== 'object') saved = {}
export let preferences = {
  mode: ['system', 'light', 'dark'].includes(saved.mode) ? saved.mode : 'system',
  light: Number.isFinite(saved.light) ? Math.max(0, Math.min(1.5, saved.light)) : 1,
  dark: Number.isFinite(saved.dark) ? Math.max(0, Math.min(1.5, saved.dark)) : 1,
}
export function applyPreferences(next = preferences) {
  preferences = next
  const root = document.documentElement
  root.dataset.theme = next.mode === 'system' ? (media.matches ? 'dark' : 'light') : next.mode
  root.style.setProperty('--glass-light-scale', next.light)
  root.style.setProperty('--glass-dark-scale', next.dark)
  try { localStorage.setItem(KEY, JSON.stringify(next)) } catch { /* Persistence is optional. */ }
  window.dispatchEvent(new CustomEvent('appearancechange', { detail: next }))
}
media?.addEventListener('change', () => applyPreferences())
