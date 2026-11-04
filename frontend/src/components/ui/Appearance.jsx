import { useEffect, useState } from 'react'
import { Surface } from './FloatingUI'
import { applyPreferences, preferences } from '../../glass/preferences'

// Reuse the shared short-form dialog; preferences apply immediately, so there
// is no unsaved draft when the user closes it.
export default function Appearance() {
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState(preferences)
  useEffect(() => {
    const sync = event => setValue(event.detail)
    window.addEventListener('appearancechange', sync)
    return () => window.removeEventListener('appearancechange', sync)
  }, [])
  const update = (key, next) => applyPreferences({ ...value, [key]: next })
  return <>
    <button className="btn appearance-trigger" onClick={() => setOpen(true)}>外观</button>
    {open && <Surface kind="dialog" label="外观" onClose={() => setOpen(false)}><div className="modal-box">
      <h2 className="modal-title">外观</h2>
      <div className="form-group"><label className="form-label" htmlFor="appearance-theme">主题</label><select id="appearance-theme" className="form-input" value={value.mode} onChange={event => update('mode', event.target.value)}><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></div>
      {['light', 'dark'].map(theme => <div className="form-group" key={theme}><label className="form-label" htmlFor={`appearance-${theme}`}>{theme === 'light' ? '浅色' : '深色'}玻璃浓度 <output>{value[theme].toFixed(2)}</output></label><input id={`appearance-${theme}`} className="form-input" type="range" min="0" max="1.5" step="0.05" value={value[theme]} onChange={event => update(theme, Number(event.target.value))}/></div>)}
      <div className="surface-actions"><button className="btn btn-primary" onClick={() => setOpen(false)}>完成</button></div>
    </div></Surface>}
  </>
}
