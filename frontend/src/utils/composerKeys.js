/**
 * 全站输入框键盘约定（2026-11-23 用户诉求：「所有的输入框都要支持 Ctrl + Enter 能在框里换行而不是发送」）。
 *
 * 约定（一处定义，全站一致）：
 * - `Enter`              → 发送 / 提交
 * - `Shift+Enter`、`Ctrl+Enter` → 多行框内换行（单行框无法换行，仅"不触发提交"）
 * - **输入法组字期间的回车一律不发送**（`isComposing` / `keyCode === 229`）——优先级高于任何修饰键组合：
 *   用户全程中文输入，组字时按回车是在选词/上屏，把半成品发出去是每天都会撞的事故。
 *
 * 实现纪律：
 * - 「发送」才 `preventDefault()`；`Shift+Enter` 一律不拦截，换行由浏览器原生插入。
 * - `Ctrl+Enter` 是唯一例外：浏览器对它**没有**默认动作（实测 textarea 里按 Ctrl+Enter，value 原样不动），
 *   单靠"不拦截"换不出行，所以由 {@link insertLineBreak} 走 `document.execCommand('insertText')` 显式插入——
 *   这是浏览器自己的编辑通道，光标位置 / 原生撤销栈 / React 受控值同步全部保留；
 *   **禁止**改成 `value = value + '\n'` 那种整串重写（会丢光标、丢撤销栈、与受控 state 打架）。
 *
 * 调用点不得再内联 `e.key === 'Enter'` 判定：`frontend/tests/composer-keys.test.js` 有静态扫描钉住
 * （扫 `frontend/src/**` 的 .jsx/.js，本文件是唯一豁免）。
 */

/** 多行框统一提示文案（放 placeholder；不要另加 title/tooltip 把同一句叠加两遍） */
export const COMPOSER_HINT = 'Enter 发送 · Shift/Ctrl+Enter 换行'

/** 聊天多行框自适应高度上限（约 6 行）；超过后框内滚动，页面不再被顶长 */
export const COMPOSER_MAX_HEIGHT = 150

/**
 * 聊天 composer 的 textarea 自适应高度（11-23-adhoc-append-sources X1）：
 * 先收回 'auto' 量真实 scrollHeight，再长到 maxHeight 为止（超出内部滚动）。
 * 调用时机：受控 value 每次变化（含换行插入）与发送清空后——清空即自然回到单行高度。
 * 只动 style.height，不碰 value/光标/撤销栈，与 {@link insertLineBreak} 的编辑通道互不干扰。
 *
 * @param {HTMLTextAreaElement|null|undefined} el
 * @param {number} [maxHeight]
 */
export function autoGrow(el, maxHeight = COMPOSER_MAX_HEIGHT) {
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, maxHeight) + 'px'
}

/**
 * 输入法组字判定。三层兜底，缺一不可：
 * 1. `e.isComposing` —— 原生 DOM 事件上的标准字段；
 * 2. `e.nativeEvent.isComposing` —— **React 合成事件不透传 isComposing**（`KeyboardEventInterface` 里没有这个字段，
 *    读 `e.isComposing` 恒为 undefined），必须回到 nativeEvent 上读，否则组字回车照样发出去；
 * 3. `keyCode === 229` —— 部分浏览器/输入法在组字期的 keydown 上只给 229（合成事件与原生事件都查）。
 *
 * 注意：CDP/Playwright 模拟组字时 keyCode 可能仍是 13，只有 isComposing 为真——第 2 层就是为这种情况兜底。
 * @param {KeyboardEvent|{isComposing?:boolean,keyCode?:number,nativeEvent?:{isComposing?:boolean,keyCode?:number}}} e
 * @returns {boolean}
 */
export function isComposingEvent(e) {
  if (!e) return false
  if (e.isComposing === true || e.keyCode === 229) return true
  const native = e.nativeEvent
  return !!native && (native.isComposing === true || native.keyCode === 229)
}

/**
 * 这次回车是否应该发送。返回 true 时调用方自行 `e.preventDefault()`（多行框必须，否则发送后还会插一个换行）
 * 并执行发送；返回 false 时调用方**什么都不做**。
 *
 * @param {KeyboardEvent} e
 * @param {{multiline?: boolean}} [options]
 *   - `multiline: true`（默认，textarea）：Enter 发送；Shift/Ctrl/Alt/Meta+Enter 一律不发送（Shift/Ctrl 用于换行）。
 *   - `multiline: false`（单行 input）：Enter 与 Shift+Enter 提交（单行本来无法换行）；Ctrl/Alt/Meta+Enter 不提交。
 * @returns {boolean}
 */
export function isSendKey(e, { multiline = true } = {}) {
  if (!e || e.key !== 'Enter') return false
  if (isComposingEvent(e)) return false                       // 组字中：绝不发送（最高优先级）
  if (e.ctrlKey || e.altKey || e.metaKey) return false        // Ctrl+Enter = 换行/不触发
  return multiline ? !e.shiftKey : true                       // 多行：Shift+Enter = 换行
}

/**
 * 在光标处插入一个换行。
 *
 * 为什么不能"只靠不拦截"：浏览器只对**裸 Enter / Shift+Enter** 有"插入换行"的默认动作，
 * `Ctrl+Enter` 在 textarea 里默认什么都不做（Playwright 实测：按 Ctrl+Enter 后 value 原样不动）。
 * 所以要满足"Ctrl+Enter 换行"，必须显式插入一次。
 *
 * 实现走 `document.execCommand('insertText')`——浏览器自己的编辑通道，而不是拼字符串改 `value`：
 * 光标位置、原生撤销栈（Ctrl+Z 能退掉这个换行）、以及真实 `input` 事件（React 受控值照常同步）全部保留。
 * `execCommand` 不可用时退回 `setRangeText` + 手动派发 input 事件（同样不整串重写 value）。
 *
 * @param {HTMLTextAreaElement|HTMLInputElement} el
 * @returns {boolean} 是否插入成功
 */
export function insertLineBreak(el) {
  if (!el) return false
  const doc = typeof document !== 'undefined' ? document : null
  if (doc && typeof doc.execCommand === 'function'
      && (typeof doc.queryCommandSupported !== 'function' || doc.queryCommandSupported('insertText'))) {
    try { if (doc.execCommand('insertText', false, '\n')) return true } catch { /* 落到兜底 */ }
  }
  const start = el.selectionStart ?? el.value.length
  const end = el.selectionEnd ?? start
  if (typeof el.setRangeText === 'function') el.setRangeText('\n', start, end, 'end')
  else el.value = el.value.slice(0, start) + '\n' + el.value.slice(end)
  const evt = typeof InputEvent !== 'undefined'
    ? new InputEvent('input', { bubbles: true, data: '\n', inputType: 'insertText' })
    : new Event('input', { bubbles: true })
  el.dispatchEvent(evt)
  return true
}

/**
 * 输入框 `onKeyDown` 的唯一入口：判定 + 该拦的拦、该放的放。
 *
 * - 发送键（见 {@link isSendKey}）→ `preventDefault()` + `send()`，返回 true；
 * - 多行框的 `Ctrl/Meta+Enter` → `preventDefault()` + 在光标处插入换行（浏览器对此无默认动作，见 {@link insertLineBreak}），返回 false；
 * - 其余（`Shift+Enter`、单行框的 `Ctrl+Enter`、组字期回车、非 Enter 键）→ **什么都不做**，返回 false，
 *   让浏览器原生行为接管（Shift+Enter 的原生换行就是这么来的）。
 *
 * @param {KeyboardEvent} e
 * @param {() => void} send 发送/提交动作
 * @param {{multiline?: boolean}} [options] 语义同 {@link isSendKey}
 * @returns {boolean} true = 本次回车触发了发送
 */
export function handleComposerKey(e, send, { multiline = true } = {}) {
  if (!e || e.key !== 'Enter') return false
  if (isComposingEvent(e)) return false                       // 组字中：不发送、也不插换行（这个回车是在选词/上屏）
  if (isSendKey(e, { multiline })) {
    e.preventDefault?.()
    if (typeof send === 'function') send()
    return true
  }
  if (multiline && (e.ctrlKey || e.metaKey)) {                // Ctrl/Cmd+Enter = 换行（Alt+Enter 不承诺，什么都不做）
    e.preventDefault?.()
    insertLineBreak(e.target)
  }
  return false
}
