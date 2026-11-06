import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'

/**
 * Toast portals into the innermost open native <dialog> (Surface drawer/dialog lives in the
 * browser top layer), falling back to document.body — the same host rule as Popconfirm and
 * TooltipCoordinator (floating-ui.md §3: put the portal inside an open native dialog so the
 * top layer does not hide it). Without this, position:fixed/z-index toasts rendered in the
 * page tree are painted UNDER an open drawer, so validation/save errors fired from inside a
 * drawer are invisible and the primary button looks dead. The toast stays position:fixed,
 * so viewport anchoring is unchanged inside a dialog.
 */
export default function Toast({ toast, onDismiss }) {
  // The dialog 'toggle' event does not bubble, so it is captured at document level. Any dialog
  // opening/closing while a toast is alive forces a re-render: the portal migrates to the new
  // innermost open dialog (or body) instead of silently vanishing with a detached dialog subtree.
  const [, setVersion] = useState(0)
  useEffect(() => {
    const ontoggle = () => setVersion(v => v + 1)
    document.addEventListener('toggle', ontoggle, true)
    return () => document.removeEventListener('toggle', ontoggle, true)
  }, [])
  if (!toast) return null
  const host = [...document.querySelectorAll('dialog[open]')].at(-1) || document.body
  return createPortal(<div className={`toast toast-${toast.type}`} role={toast.type === 'error' ? 'alert' : 'status'}><span>{toast.msg}</span><button className="toast-close" onClick={onDismiss} aria-label="关闭通知">×</button></div>, host)
}
