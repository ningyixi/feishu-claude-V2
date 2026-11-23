import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative, sep } from 'node:path'
import { COMPOSER_HINT, handleComposerKey, isComposingEvent, isSendKey } from '../src/utils/composerKeys.js'

/**
 * 全站输入框键盘约定（2026-11-23 用户诉求：「所有的输入框都要支持 Ctrl + Enter 能在框里换行而不是发送」）。
 *
 * 两层钉住：
 * 1) `isSendKey` 真值表——含**输入法组字**这条最容易漏、事故代价最高的分支（AC3 的单测级证据）；
 * 2) 静态扫描（AC5 防回归）——`frontend/src/**` 里不得再出现内联 `e.key === 'Enter'` 判定，
 *    8 处 composer 调用点必须全部走 helper；提示文案单一来源、且过项目措辞禁词纪律
 *    （口径同 `src/test/java/.../SupplierPromptWordingTest`：禁游戏类说辞）。
 *
 * 浏览器级行为（真按 Ctrl+Enter 出现换行且零请求）见 `frontend/tests/composer-keys.spec.js`。
 * 跑法：`node --test frontend/tests/composer-keys.test.js`
 */

const here = dirname(fileURLToPath(import.meta.url))
const srcRoot = join(here, '..', 'src')
const HELPER = 'src/utils/composerKeys.js'   // rel() 输出统一用正斜杠，这里不能用 join（Windows 会出反斜杠）

/** 键盘事件桩：只给需要的字段，其余按"没按"处理（与浏览器 KeyboardEvent 默认值一致） */
const ev = (over = {}) => ({
  key: 'Enter', isComposing: false, keyCode: 0,
  shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, ...over,
})

/** 递归收集 src 下的 .jsx / .js 源文件（跳过 node_modules） */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules') continue
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (/\.(jsx|js)$/.test(name)) out.push(full)
  }
  return out
}

const sources = walk(srcRoot)
const rel = f => relative(join(here, '..'), f).split(sep).join('/')
const read = f => readFileSync(f, 'utf8')

// ===== AC1 / AC2：多行 composer 真值表 =====

test('AC1/AC2 多行框：裸 Enter 发送，Shift/Ctrl/Alt/Meta+Enter 一律不发送（换行交给浏览器原生）', () => {
  assert.equal(isSendKey(ev()), true, '多行 Enter 应发送')
  assert.equal(isSendKey(ev({}), {}), true, 'options 缺省即多行')
  assert.equal(isSendKey(ev({ shiftKey: true }), { multiline: true }), false, 'Shift+Enter 必须换行，不发送')
  assert.equal(isSendKey(ev({ ctrlKey: true }), { multiline: true }), false, 'Ctrl+Enter 必须换行，不发送（本任务的用户诉求本身）')
  assert.equal(isSendKey(ev({ altKey: true }), { multiline: true }), false, 'Alt+Enter 不发送')
  assert.equal(isSendKey(ev({ metaKey: true }), { multiline: true }), false, 'Meta+Enter 不发送')
  assert.equal(isSendKey(ev({ ctrlKey: true, shiftKey: true }), { multiline: true }), false, 'Ctrl+Shift+Enter 不发送')
  assert.equal(isSendKey(ev({ ctrlKey: true, altKey: true, metaKey: true, shiftKey: true }), { multiline: true }), false,
    '四修饰键全按也不发送')
})

test('AC4 单行 input：Enter 与 Shift+Enter 提交，Ctrl/Alt/Meta+Enter 不提交（单行无法换行，故不承诺换行）', () => {
  const single = { multiline: false }
  assert.equal(isSendKey(ev(), single), true, '单行 Enter 应提交')
  assert.equal(isSendKey(ev({ shiftKey: true }), single), true, '单行 Shift+Enter 仍提交（历史行为，且单行插不进换行）')
  assert.equal(isSendKey(ev({ ctrlKey: true }), single), false, '单行 Ctrl+Enter 不得提交')
  assert.equal(isSendKey(ev({ altKey: true }), single), false, '单行 Alt+Enter 不得提交')
  assert.equal(isSendKey(ev({ metaKey: true }), single), false, '单行 Meta+Enter 不得提交')
})

// ===== AC3：输入法组字（本任务最容易漏、代价最高的一条） =====

test('AC3 输入法组字期间一律不发送：isComposing 与 keyCode=229 兜底，优先级高于任何修饰键组合', () => {
  const combos = [
    {}, { shiftKey: true }, { ctrlKey: true }, { altKey: true }, { metaKey: true },
    { ctrlKey: true, shiftKey: true },
  ]
  for (const multiline of [true, false]) {
    for (const mods of combos) {
      assert.equal(isSendKey(ev({ ...mods, isComposing: true }), { multiline }), false,
        `isComposing=true 时不得发送（multiline=${multiline}, mods=${JSON.stringify(mods)}）`)
      assert.equal(isSendKey(ev({ ...mods, keyCode: 229 }), { multiline }), false,
        `keyCode=229 兜底时不得发送（multiline=${multiline}, mods=${JSON.stringify(mods)}）`)
    }
  }
  // 组字期的 keydown 在部分浏览器上 key 是 'Process'（不是 'Enter'）——同样不得发送
  assert.equal(isSendKey(ev({ key: 'Process', isComposing: true })), false, "key='Process' 不发送")
  assert.equal(isSendKey(ev({ key: 'Process', keyCode: 229 })), false, "key='Process' + 229 不发送")
  // 组字结束后（isComposing=false）恢复正常发送，不会被永久卡住
  assert.equal(isSendKey(ev({ isComposing: false, keyCode: 0 })), true, '组字结束后 Enter 恢复发送')
  assert.equal(isComposingEvent(ev({ isComposing: true })), true)
  assert.equal(isComposingEvent(ev({ keyCode: 229 })), true)
  assert.equal(isComposingEvent(ev()), false)
})

test('AC3 React 合成事件不透传 isComposing：必须回读 nativeEvent，否则组字回车照样发出去（Playwright 实测抓到的真 bug）', () => {
  // React 的 KeyboardEventInterface 里没有 isComposing（react-dom.development.js:7205），
  // 所以 onKeyDown 拿到的 e.isComposing 恒为 undefined；组字期的 keyCode 也不一定是 229（CDP 模拟时就是 13）。
  const reactSynthetic = composing => ({
    key: 'Enter', keyCode: 13, isComposing: undefined,
    ctrlKey: false, shiftKey: false, altKey: false, metaKey: false,
    nativeEvent: { isComposing: composing, keyCode: 13 },
    defaultPrevented: false, preventDefault() { this.defaultPrevented = true },
  })
  assert.equal(isComposingEvent(reactSynthetic(true)), true, 'nativeEvent.isComposing=true 必须识别为组字')
  assert.equal(isSendKey(reactSynthetic(true)), false, 'React 合成事件组字态不得判定为发送')
  assert.equal(isSendKey(reactSynthetic(false)), true, '非组字态照常发送（不能误伤）')
  assert.equal(isComposingEvent({ key: 'Enter', keyCode: 13, nativeEvent: { keyCode: 229 } }), true,
    'nativeEvent.keyCode=229 兜底同样生效')

  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    const send = () => { sent += 1 }
    const e = reactSynthetic(true)
    e.target = {}
    assert.equal(handleComposerKey(e, send), false)
    assert.equal(sent, 0, 'React 合成事件 + 组字态：不得发送')
    assert.deepEqual(spy.calls, [], 'React 合成事件 + 组字态：不得插入换行')
    assert.equal(e.defaultPrevented, false, '组字回车不拦截，交给浏览器上屏候选词')
  })
})

test('非 Enter 键、空事件一律不发送（不干扰浏览器默认行为）', () => {
  for (const key of ['a', 'Enter ', 'NumpadEnter', 'Escape', 'Tab', '']) {
    assert.equal(isSendKey(ev({ key })), false, `key='${key}' 不发送`)
  }
  assert.equal(isSendKey(null), false, 'null 事件不抛错')
  assert.equal(isSendKey(undefined, { multiline: false }), false, 'undefined 事件不抛错')
  assert.equal(isSendKey({}), false, '缺 key 的事件不发送')
})

// ===== handleComposerKey：调用点唯一入口的行为（发送 / 原生放行 / Ctrl+Enter 显式插换行） =====

/** 键盘事件桩：带 preventDefault 记录 + target */
const kev = (over = {}, target = {}) => ({
  key: 'Enter', isComposing: false, keyCode: 0,
  shiftKey: false, ctrlKey: false, altKey: false, metaKey: false,
  defaultPrevented: false, preventDefault() { this.defaultPrevented = true },
  target, ...over,
})

/** 临时替换 globalThis.document，捕获 execCommand 调用（node 环境没有 DOM） */
function withDocument(doc, fn) {
  const had = 'document' in globalThis
  const prev = globalThis.document
  globalThis.document = doc
  try { return fn() } finally { if (had) globalThis.document = prev; else delete globalThis.document }
}
const execSpy = (ok = true) => {
  const calls = []
  return { calls, doc: { queryCommandSupported: () => true, execCommand: (...a) => { calls.push(a); return ok } } }
}

test('handleComposerKey 多行：裸 Enter 发送并 preventDefault；Shift+Enter 完全不拦截（原生换行）', () => {
  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    const enter = kev()
    assert.equal(handleComposerKey(enter, () => { sent += 1 }), true, '裸 Enter 应发送')
    assert.equal(sent, 1)
    assert.equal(enter.defaultPrevented, true, '发送必须 preventDefault，否则发送后还会多插一个换行')

    const shift = kev({ shiftKey: true })
    assert.equal(handleComposerKey(shift, () => { sent += 1 }), false)
    assert.equal(sent, 1, 'Shift+Enter 不得发送')
    assert.equal(shift.defaultPrevented, false, 'Shift+Enter 不得 preventDefault（换行交给浏览器原生行为）')
    assert.deepEqual(spy.calls, [], 'Shift+Enter 不需要我们插入换行')
  })
})

test('handleComposerKey 多行：Ctrl/Cmd+Enter 不发送，改由 execCommand(insertText) 在光标处插入换行', () => {
  // 浏览器对 Ctrl+Enter 没有默认动作（Playwright 实测 value 原样不动），所以必须显式插入；
  // 但插入走 execCommand —— 光标位置、原生撤销栈、React 受控值同步全部保留。
  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    const send = () => { sent += 1 }
    for (const mods of [{ ctrlKey: true }, { metaKey: true }, { ctrlKey: true, shiftKey: true }]) {
      const e = kev(mods)
      assert.equal(handleComposerKey(e, send), false, `${JSON.stringify(mods)}+Enter 不得发送`)
      assert.equal(e.defaultPrevented, true, `${JSON.stringify(mods)}+Enter 应拦掉默认行为再插换行`)
    }
    assert.equal(sent, 0, '任何 Ctrl/Cmd 组合都不得发送')
    assert.deepEqual(spy.calls, [['insertText', false, '\n'], ['insertText', false, '\n'], ['insertText', false, '\n']],
      '插入换行必须走 execCommand("insertText")，不得整串重写 value')
  })

  // Alt+Enter：不发送，也不承诺换行（提示文案只写了 Shift/Ctrl）——完全不动
  const altSpy = execSpy()
  withDocument(altSpy.doc, () => {
    let sent = 0
    const e = kev({ altKey: true })
    assert.equal(handleComposerKey(e, () => { sent += 1 }), false)
    assert.equal(sent, 0)
    assert.equal(e.defaultPrevented, false, 'Alt+Enter 不拦截、不插入')
    assert.deepEqual(altSpy.calls, [])
  })
})

test('handleComposerKey 单行 input：Enter/Shift+Enter 提交，Ctrl+Enter 既不提交也不插换行', () => {
  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    const send = () => { sent += 1 }
    const single = { multiline: false }
    assert.equal(handleComposerKey(kev(), send, single), true, '单行 Enter 提交')
    assert.equal(handleComposerKey(kev({ shiftKey: true }), send, single), true, '单行 Shift+Enter 仍提交')
    assert.equal(sent, 2)
    const ctrl = kev({ ctrlKey: true })
    assert.equal(handleComposerKey(ctrl, send, single), false, '单行 Ctrl+Enter 不得提交')
    assert.equal(sent, 2)
    assert.equal(ctrl.defaultPrevented, false, '单行框 Ctrl+Enter 什么都不做（插不进换行，也不提交）')
    assert.deepEqual(spy.calls, [], '单行框不得插入换行')
  })
})

test('handleComposerKey 组字期间：不发送、不插换行、不拦截（AC3 的行为面）', () => {
  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    const send = () => { sent += 1 }
    for (const multiline of [true, false]) {
      for (const mods of [{}, { ctrlKey: true }, { shiftKey: true }, { ctrlKey: true, shiftKey: true }]) {
        for (const composing of [{ isComposing: true }, { keyCode: 229 }]) {
          const e = kev({ ...mods, ...composing })
          assert.equal(handleComposerKey(e, send, { multiline }), false)
          assert.equal(e.defaultPrevented, false, '组字回车不得拦截（浏览器要自己上屏候选词）')
        }
      }
    }
    assert.equal(sent, 0, '组字期间一次也不许发送')
    assert.deepEqual(spy.calls, [], '组字期间不许插入换行')
    // 组字结束后立刻恢复
    assert.equal(handleComposerKey(kev(), send), true)
    assert.equal(sent, 1)
  })
})

test('handleComposerKey 兜底：execCommand 不可用时用 setRangeText 在光标处插入并派发 input 事件', () => {
  const ranges = []
  const events = []
  const target = {
    value: 'ab', selectionStart: 2, selectionEnd: 2,
    setRangeText: (text, s, e, dir) => ranges.push([text, s, e, dir]),
    dispatchEvent: evt => events.push(evt.type),
  }
  // 1) 没有 document（node 环境 / SSR）
  const noDocSentinel = handleComposerKey(kev({ ctrlKey: true }, target), () => {})
  assert.equal(noDocSentinel, false)
  assert.deepEqual(ranges, [['\n', 2, 2, 'end']], '按光标位置插入，不整串重写')
  assert.deepEqual(events, ['input'], '必须派发 input 事件，React 受控值才会同步')

  // 2) execCommand 存在但返回 false
  ranges.length = 0; events.length = 0
  withDocument({ queryCommandSupported: () => true, execCommand: () => false },
    () => handleComposerKey(kev({ ctrlKey: true }, target), () => {}))
  assert.deepEqual(ranges, [['\n', 2, 2, 'end']])
  assert.deepEqual(events, ['input'])
})

test('handleComposerKey 非 Enter 键与空事件一律不动（不干扰浏览器默认行为）', () => {
  const spy = execSpy()
  withDocument(spy.doc, () => {
    let sent = 0
    for (const key of ['a', 'Escape', 'Tab', ' ', 'NumpadEnter']) {
      const e = kev({ key })
      assert.equal(handleComposerKey(e, () => { sent += 1 }), false)
      assert.equal(e.defaultPrevented, false, `key='${key}' 不得被拦截`)
    }
    assert.equal(handleComposerKey(null, () => { sent += 1 }), false, 'null 事件不抛错')
    assert.equal(handleComposerKey(undefined, undefined, { multiline: false }), false, 'undefined 事件/回调不抛错')
    assert.equal(handleComposerKey(kev(), undefined), true, 'send 缺省时仍算发送键（不抛错）')
    assert.equal(sent, 0)
    assert.deepEqual(spy.calls, [])
  })
})

// ===== AC5：静态扫描防回归 =====

/** 内联 Enter 判定的各种写法（ helper 自身是唯一豁免 ） */
const INLINE_ENTER = [
  [/\.key\s*===?\s*['"`]Enter['"`]/, "e.key === 'Enter'"],
  [/['"`]Enter['"`]\s*===?\s*[\w$.]+\.key\b/, "'Enter' === e.key"],
  [/\.keyCode\s*===?\s*13\b/, 'e.keyCode === 13'],
  [/\.which\s*===?\s*13\b/, 'e.which === 13'],
]

/**
 * 非 composer 的键盘语义豁免（按行内特征串匹配，不是整文件放行）。
 * 每条都必须"仍在使用"——名单里的行消失了本测试同样报错，防止豁免名单腐烂成盲区。
 */
const NON_COMPOSER_ALLOW = [
  {
    file: 'src/components/ui/FloatingUI.jsx',
    probe: "(event.key === 'Enter' || event.key === ' ')",
    why: 'data-document 长文阅读器的键盘激活（Enter/Space 打开），是 floating-ui 规范要求的可访问性行为，与"发送"无关',
  },
]

test('AC5 静态扫描：src 下除 helper 外无任何内联 Enter 判定（新加输入框必须复用 isSendKey）', () => {
  const jsx = sources.filter(f => f.endsWith('.jsx'))
  const offenders = []
  const usedAllow = new Set()
  for (const file of sources) {
    const path = rel(file)
    if (path === HELPER) continue
    const text = read(file)
    text.split('\n').forEach((line, i) => {
      for (const [re, label] of INLINE_ENTER) {
        if (!re.test(line)) continue
        const allow = NON_COMPOSER_ALLOW.find(a => a.file === path && line.includes(a.probe))
        if (allow) { usedAllow.add(allow); continue }
        offenders.push(`${path}:${i + 1} 内联判定「${label}」→ ${line.trim().slice(0, 140)}`)
      }
    })
  }
  console.log(`[静态扫描] src 下源文件 ${sources.length} 个（.jsx ${jsx.length} 个 / .js ${sources.length - jsx.length} 个），豁免 helper 1 个 + 非 composer 键盘语义 ${usedAllow.size} 处`)
  assert.ok(sources.length >= 20, `扫描面异常：只扫到 ${sources.length} 个文件，walk 可能失效`)
  assert.deepEqual(offenders, [],
    `发现内联 Enter 判定（请改用 utils/composerKeys.js 的 isSendKey，判定只能一处定义）:\n${offenders.join('\n')}`)
  const stale = NON_COMPOSER_ALLOW.filter(a => !usedAllow.has(a))
  assert.deepEqual(stale.map(a => `${a.file} → ${a.probe}`), [],
    '豁免名单里的行已不存在，请删掉该条豁免（留着会变成盲区）')
})

test('AC5 静态扫描：8 处 composer 调用点全部走 handleComposerKey，且没有第 9 处野生实现', () => {
  const EXPECT = {
    'src/pages/Supplier.jsx': 4,       // 节点补充指示 / 卡点答复 / 工作台对话区（单行） / DevChat 原始诉求
    'src/pages/AdHoc.jsx': 2,          // 待确认卡答复（单行） / 对话框（单行）
    'src/pages/Employees.jsx': 1,      // 员工对话（单行）
    'src/pages/E2ETest.jsx': 1,        // 流水线聊天（多行）
  }
  const actual = {}
  for (const file of sources) {
    if (rel(file) === HELPER) continue           // helper 自身的函数定义不算调用点
    const n = (read(file).match(/handleComposerKey\(/g) || []).length
    if (n) actual[rel(file)] = n
  }
  console.log('[静态扫描] handleComposerKey 调用点分布：', JSON.stringify(actual), '合计', Object.values(actual).reduce((a, b) => a + b, 0))
  assert.deepEqual(actual, EXPECT,
    '调用点清单与实现不符：新增 composer 请复用 utils/composerKeys 的 handleComposerKey 并把它加进本清单；删掉的请同步移除')
  for (const file of Object.keys(EXPECT)) {
    assert.match(read(join(here, '..', file)), /from '\.\.\/utils\/composerKeys'/, `${file} 必须从 utils/composerKeys 导入`)
  }
})

// ===== 提示文案：单一来源 + 措辞禁词纪律 =====

test('提示文案统一为「Enter 发送 · Shift/Ctrl+Enter 换行」，单一来源且无游戏类说辞', () => {
  assert.equal(COMPOSER_HINT, 'Enter 发送 · Shift/Ctrl+Enter 换行')
  // 措辞纪律（口径同 SupplierPromptWordingTest）：不得出现游戏类说辞
  for (const banned of ['玩家', '开一局', '通关', '存档', '打怪', '副本', '任务奖励']) {
    assert.ok(!COMPOSER_HINT.includes(banned), `提示文案含禁词「${banned}」`)
  }
  // 文案只在 helper 里定义一次：src 中不得出现硬编码副本（避免各页各写一句、日后走样）
  const dup = sources.filter(f => rel(f) !== HELPER && read(f).includes('Enter 发送 · Shift/Ctrl+Enter 换行'))
  assert.deepEqual(dup.map(rel), [], '提示文案必须引用 COMPOSER_HINT，不得硬编码副本')
  // 多行 composer 所在页面确实引用了该常量
  for (const file of ['src/pages/Supplier.jsx', 'src/pages/E2ETest.jsx']) {
    assert.match(read(join(here, '..', file)), /COMPOSER_HINT/, `${file} 的多行框提示须引用 COMPOSER_HINT`)
  }
})
