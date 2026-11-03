import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, forwardRef, useImperativeHandle, cloneElement, isValidElement } from 'react'
import { createPortal } from 'react-dom'
import { useBlocker, useSearchParams } from 'react-router-dom'

const UI = createContext(null)
const Form = createContext(null)
export const useFormSaved = () => useContext(Form)?.commit || (() => {})
export function useQueryParam(key, fallback = null) {
  const [params, setParams] = useSearchParams()
  const raw = params.get(key), value = raw == null ? fallback : typeof fallback === 'boolean' ? raw === '1' : raw
  const update = useCallback(next => { const query = new URLSearchParams(window.location.search); if (next == null || next === false || next === fallback) query.delete(key); else query.set(key, next === true ? '1' : String(next)); setParams(query) }, [key, fallback, setParams])
  return [value, update]
}
export function useDirtyValue(value, identity) {
  const initial = useRef({ identity, value: JSON.stringify(value) })
  if (initial.current.identity !== identity) initial.current = { identity, value: JSON.stringify(value) }
  return JSON.stringify(value) !== initial.current.value
}
const focusable = 'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]'
const snapshot = root => JSON.stringify([...root.querySelectorAll('input,textarea,select')].map((el, i) => [el.name || el.id || i, el.type === 'checkbox' || el.type === 'radio' ? el.checked : el.value]))
const restoreFocus = element => requestAnimationFrame(() => { const open = [...window.document.querySelectorAll('dialog[open]')].at(-1); if (element?.isConnected && !element.closest('[inert]') && (!open || open.contains(element))) element.focus({ preventScroll: true }) })
export const useConfirm = () => useContext(UI).confirm

export function FloatingUIProvider({ children }) {
  const [request, setRequest] = useState(null)
  const pending = useRef(null)
  const trigger = useRef(null)
  useEffect(() => { const remember = event => { const element = event.target.closest?.('button,a,[role="button"]'); if (element) trigger.current = element }; window.document.addEventListener('pointerdown', remember, true); window.document.addEventListener('focusin', remember, true); return () => { window.document.removeEventListener('pointerdown', remember, true); window.document.removeEventListener('focusin', remember, true) } }, [])
  const dirtyForms = useRef(new Set())
  const [dirtyCount, setDirtyCount] = useState(0)
  const [document, setDocument] = useState(null)
  const confirm = useCallback(options => {
    if (pending.current) return Promise.resolve(false)
    return new Promise(resolve => { pending.current = resolve; setRequest({ ...options, anchor: options.anchor || (trigger.current?.isConnected ? trigger.current : window.document.activeElement) }) })
  }, [])
  const finish = useCallback(result => { const resolve = pending.current; pending.current = null; setRequest(null); resolve?.(result) }, [])
  const registerDirty = useCallback((key, dirty) => { if (dirty) dirtyForms.current.add(key); else dirtyForms.current.delete(key); setDirtyCount(dirtyForms.current.size) }, [])
  const discard = useCallback(() => confirm({ title: '放弃未保存的更改？', body: '未保存的更改将丢失。', confirmLabel: '放弃更改', cancelLabel: '继续编辑' }), [confirm])
  useEffect(() => {
    const unload = event => { if (dirtyForms.current.size) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', unload); return () => window.removeEventListener('beforeunload', unload)
  }, [])
  const value = useMemo(() => ({ confirm, registerDirty, dirtyCount, discard, hasDirty: () => dirtyForms.current.size > 0 }), [confirm, registerDirty, dirtyCount, discard])
  return <UI.Provider value={value}>
    {children}
    <TooltipCoordinator onDocument={setDocument} />
    {request && (request.kind === 'popconfirm' && request.anchor?.isConnected
      ? <Popconfirm request={request} onFinish={finish} />
      : <Surface onClose={() => finish(false)} label={request.title}>
          <div className="modal-box confirmation-box">
            <div className="confirmation-icon" aria-hidden="true">!</div>
            <div className="modal-title">{request.title}</div>
            <div className="modal-body">{request.body}</div>
            <div className="surface-actions"><button className="btn" data-initial-focus autoFocus onClick={() => finish(false)}>{request.cancelLabel || '取消'}</button><button className="btn btn-danger" onClick={() => finish(true)}>{request.confirmLabel}</button></div>
          </div>
        </Surface>)}
    {document && <Surface kind="drawer" label={document.title} onClose={() => setDocument(null)}><div className="modal-box document-reader"><div className="modal-title">{document.title}</div><pre className="document-text">{document.body}</pre></div></Surface>}
  </UI.Provider>
}

function useFormGuard(root, enabled, suppliedDirty) {
  const { registerDirty, discard } = useContext(UI)
  const key = useId()
  const baseline = useRef(null)
  const [trackedDirty, setDirty] = useState(false)
  const dirty = suppliedDirty ?? trackedDirty
  const bypass = useRef(false)
  useLayoutEffect(() => { if (enabled && root.current && baseline.current === null) baseline.current = snapshot(root.current) })
  useEffect(() => { registerDirty(key, dirty); return () => registerDirty(key, false) }, [key, dirty, registerDirty])
  const changed = event => { if (event && !root.current?.contains(event.target)) return; if (enabled) queueMicrotask(() => { if (root.current) setDirty(snapshot(root.current) !== baseline.current) }) }
  const commit = () => { baseline.current = null; setDirty(false); registerDirty(key, false) }
  const close = async callback => { if (!dirty || await discard()) { commit(); callback() } }
  const capture = event => {
    if (!dirty || bypass.current || !root.current?.contains(event.target)) return
    const button = event.target.closest('button,a')
    if (!button || button.hasAttribute('data-surface-close')) return
    const text = button.textContent.replace(/^[←‹↩\s]+/, '').trim()
    if (!/^(取消|关闭|收起|返回|取消编辑)$/.test(text)) return
    event.preventDefault(); event.stopPropagation()
    close(() => { bypass.current = true; button.click(); bypass.current = false })
  }
  return { dirty, changed, close, capture, commit }
}

/** One behaviour foundation; the native top layer provides modal focus containment and inert background. */
export function Surface({ kind = 'dialog', onClose, label, form = false, dirty, children }) {
  const root = useRef(null)
  const opener = useRef(null)
  const titleId = useId()
  const guard = useFormGuard(root, form, dirty)
  const [pageHost, setPageHost] = useState(null)
  useLayoutEffect(() => { if (kind === 'page') setPageHost(window.document.querySelector('.main')) }, [kind])
  useLayoutEffect(() => {
    if (kind === 'page') return
    const dialog = root.current; opener.current = window.document.activeElement
    const title = dialog.querySelector('.modal-title'); if (title) title.id = titleId
    dialog.showModal()
    const focus = dialog.querySelector('[data-initial-focus]') || dialog.querySelector('input:not(:disabled),textarea:not(:disabled),select:not(:disabled)') || dialog.querySelector(focusable)
    focus?.focus({ preventScroll: true })
    return () => { dialog.close(); restoreFocus(opener.current) }
  }, [kind, titleId])
  const close = () => guard.close(onClose)
  const closeButton = <button type="button" className="btn surface-close" data-surface-close aria-label="关闭" onClick={close}>×</button>
  const body = <Form.Provider value={guard}>{isValidElement(children) ? cloneElement(children, {}, closeButton, children.props.children) : <>{closeButton}{children}</>}</Form.Provider>
  if (kind === 'page') return pageHost && createPortal(<section ref={root} className="page-surface" aria-label={label} onInput={guard.changed} onChange={guard.changed} onClickCapture={guard.capture}>{body}</section>, pageHost)
  return createPortal(<dialog ref={root} className={`modal-mask surface-host surface-${kind}`} aria-label={label} aria-labelledby={label ? undefined : titleId}
    onCancel={event => { event.preventDefault(); close() }} onClick={event => { if (event.target === root.current) close() }}
    onInput={guard.changed} onChange={guard.changed} onClickCapture={guard.capture}>{body}</dialog>, window.document.body)
}

/** Register existing inline page forms without changing their visual structure. */
export const DraftPage = forwardRef(function DraftPage({ children, enabled = true, dirty }, ref) {
  const root = useRef(null), guard = useFormGuard(root, enabled, dirty)
  useImperativeHandle(ref, () => ({ commit: guard.commit }))
  return <Form.Provider value={guard}><div ref={root} className="draft-page" onInput={guard.changed} onChange={guard.changed} onClickCapture={guard.capture}>{children}</div></Form.Provider>
})

const navigationIdentity = location => { const query = new URLSearchParams(location.search); return location.pathname + ['job','view','employee','instance','create','task'].map(key => ':' + (query.get(key) || '')).join('') }

export function NavigationGuard() {
  const { hasDirty, discard } = useContext(UI)
  const blocker = useBlocker(({ currentLocation, nextLocation }) => hasDirty() && navigationIdentity(currentLocation) !== navigationIdentity(nextLocation))
  const prompting = useRef(false)
  useEffect(() => {
    if (blocker.state !== 'blocked' || prompting.current) return
    prompting.current = true
    discard().then(accepted => { prompting.current = false; if (accepted) blocker.proceed(); else blocker.reset() })
  }, [blocker, discard])
  return null
}

function placement(anchor, box) {
  const rect = anchor.getBoundingClientRect(), margin = 12
  const width = box.offsetWidth, height = box.offsetHeight
  const above = rect.bottom + height + 10 > window.innerHeight && rect.top > height + 10
  return { left: Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin)), top: Math.max(margin, above ? rect.top - height - 8 : Math.min(rect.bottom + 8, window.innerHeight - height - margin)), transformOrigin: above ? 'bottom left' : 'top left' }
}

function Popconfirm({ request, onFinish }) {
  const root = useRef(null)
  const title = useId()
  const [position, setPosition] = useState({ visibility: 'hidden' })
  const host = request.anchor.closest('dialog[open]') || window.document.body
  useLayoutEffect(() => {
    const update = () => { if (!request.anchor.isConnected) return onFinish(false); setPosition(placement(request.anchor, root.current)) }
    update(); root.current.querySelector('button')?.focus()
    const outside = event => { if (!root.current.contains(event.target) && !request.anchor.contains(event.target)) onFinish(false) }
    const key = event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onFinish(false) } }
    const observer = new ResizeObserver(update); observer.observe(root.current)
    window.addEventListener('resize', update); window.addEventListener('scroll', update, true); window.document.addEventListener('pointerdown', outside, true); window.document.addEventListener('keydown', key, true)
    return () => { observer.disconnect(); window.removeEventListener('resize', update); window.removeEventListener('scroll', update, true); window.document.removeEventListener('pointerdown', outside, true); window.document.removeEventListener('keydown', key, true); restoreFocus(request.anchor) }
  }, [request, onFinish])
  return createPortal(<div className="modal-box popconfirm-box" role="alertdialog" aria-labelledby={title} ref={root} style={position}>
    <div className="modal-title" id={title}>{request.title}</div><div className="modal-body">{request.body}</div>
    <div className="surface-actions"><button className="btn" onClick={() => onFinish(false)}>取消</button><button className="btn btn-danger" onClick={() => onFinish(true)}>{request.confirmLabel}</button></div>
  </div>, host)
}

/** Delegation keeps one coordinated tooltip across dynamic tables and polling updates. */
function TooltipCoordinator({ onDocument }) {
  const [hint, setHint] = useState(null)
  const [position, setPosition] = useState({ visibility: 'hidden' })
  const root = useRef(null), timer = useRef(null), current = useRef(null), lastShown = useRef(0), touch = useRef(false)
  const id = useId()
  useEffect(() => {
    const hide = () => { clearTimeout(timer.current); if (root.current) lastShown.current = Date.now(); current.current = null; setHint(null) }
    const show = (element, immediate = false) => {
      clearTimeout(timer.current)
      if (!element || element.getAttribute('data-tooltip')?.trim() === '') return hide()
      current.current = element
      timer.current = setTimeout(() => { if (element.isConnected) { lastShown.current = Date.now(); setPosition({ visibility: 'hidden' }); setHint({ element, text: element.getAttribute('data-tooltip') }) } }, immediate || Date.now() - lastShown.current < 1200 ? 0 : 500)
    }
    const target = element => element instanceof Element ? element.closest('[data-tooltip]') : null
    const over = event => { if (event.pointerType === 'touch') return; const element = target(event.target); if (element && !element.contains(event.relatedTarget)) show(element) }
    const out = event => { const element = target(event.target); if (element && !element.contains(event.relatedTarget)) hide() }
    const focus = event => show(target(event.target))
    const key = event => { if (event.key === 'Escape') hide(); if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-document]')) { event.preventDefault(); onDocument({ title: event.target.getAttribute('data-document-title') || '工作 SPEC', body: event.target.getAttribute('data-document') }) } }
    const pointer = event => { touch.current = event.pointerType === 'touch'; if (!target(event.target)) hide(); if (touch.current && target(event.target)) show(target(event.target), true) }
    const click = event => {
      const doc = event.target.closest('[data-document]'); if (doc) { event.preventDefault(); event.stopPropagation(); hide(); onDocument({ title: doc.getAttribute('data-document-title') || '工作 SPEC', body: doc.getAttribute('data-document') }); return }
      const element = target(event.target)
      const control = event.target.closest('button,a,input,select,textarea')
      if (touch.current && element && (!control || control.disabled)) { event.stopPropagation(); event.preventDefault(); show(element, true) }
    }
    const events = { pointerover: over, pointerout: out, focusin: focus, focusout: hide, keydown: key, pointerdown: pointer, click }
    for (const [name, handler] of Object.entries(events)) window.document.addEventListener(name, handler, true)
    window.addEventListener('blur', hide)
    return () => { clearTimeout(timer.current); for (const [name, handler] of Object.entries(events)) window.document.removeEventListener(name, handler, true); window.removeEventListener('blur', hide) }
  }, [onDocument])
  useLayoutEffect(() => {
    if (!hint) return
    const element = hint.element, old = element.getAttribute('aria-describedby')
    element.setAttribute('aria-describedby', [old, id].filter(Boolean).join(' '))
    const update = () => { if (!element.isConnected) { setHint(null); return } setPosition(placement(element, root.current)) }
    update(); const observer = new ResizeObserver(update); observer.observe(root.current)
    const live = new MutationObserver(() => { if (!element.isConnected) setHint(null); else { const text = element.getAttribute('data-tooltip'); if (text !== hint.text) setHint(text ? { element, text } : null) } }); live.observe(element, { attributes: true, attributeFilter: ['data-tooltip'] }); live.observe(window.document.body, { childList: true, subtree: true })
    window.addEventListener('resize', update); window.addEventListener('scroll', update, true)
    return () => { observer.disconnect(); live.disconnect(); if (old) element.setAttribute('aria-describedby', old); else element.removeAttribute('aria-describedby'); window.removeEventListener('resize', update); window.removeEventListener('scroll', update, true) }
  }, [hint, id])
  return hint && createPortal(<div ref={root} id={id} role="tooltip" className="ui-tooltip" style={position}>{hint.text}</div>, hint.element.closest('dialog[open]') || window.document.body)
}
