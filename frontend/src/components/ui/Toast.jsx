export default function Toast({ toast, onDismiss }) {
  if (!toast) return null
  return <div className={`toast toast-${toast.type}`} role={toast.type === 'error' ? 'alert' : 'status'}><span>{toast.msg}</span><button className="toast-close" onClick={onDismiss} aria-label="关闭通知">×</button></div>
}
