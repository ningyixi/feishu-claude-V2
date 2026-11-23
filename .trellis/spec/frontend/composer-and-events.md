# Composer & DOM Events（输入框与浏览器事件规范）

适用范围：`frontend/src/**` 所有带键盘交互的输入框（composer）与依赖原生 DOM 事件字段的功能。
唯一实现点：`frontend/src/utils/composerKeys.js`（判定 + 动作 + 提示文案 + 自适应高度）。
钉住它的测试：`frontend/tests/composer-keys.test.js`（真值表 + 静态扫描）、`frontend/tests/composer-keys.spec.js`（真浏览器行为）。

## 1. React 合成键盘事件不透传 `isComposing` —— 判输入法组字必须走 `nativeEvent` 兜底

`react-dom` 的 `KeyboardEventInterface` **没有** `isComposing` 字段：`onKeyDown` 里读 `e.isComposing`
恒为 `undefined`。只写 `if (e.isComposing) return` 的组字防护是**假的**——中文用户在组字态按回车
（选词/上屏）时 keyCode 可能仍是 13，半成品直接被发送出去（本项目实测：POST 从 1 变 2，
2026-11-23 Playwright CDP `Input.imeSetComposition` 复现）。

正确判定（三层兜底，缺一不可，见 `isComposingEvent`）：

1. `e.isComposing === true` —— 原生 DOM 事件的标准字段（非 React 通道时有值）；
2. `e.nativeEvent.isComposing === true` —— **React 合成事件必须回到 nativeEvent 上读**；
3. `e.keyCode === 229 || e.nativeEvent.keyCode === 229` —— 部分浏览器/输入法组字期只给 229。

组字判定优先级高于一切修饰键组合：组字期间的回车一律不发送、不插换行、也不 `preventDefault()`
（浏览器要自己上屏候选词）。

## 2. PRD/指令不得写死与平台行为冲突的实现机制 —— 写"要什么结果 + 不许退化什么"

反例（真实发生过）：上一棒 PRD 命令"不许自己插换行、靠浏览器默认行为"，而浏览器对
`Ctrl+Enter` **本来就没有**插入换行的默认动作（Playwright 实测：按下后 value 原样不动）——
按指令实现必然达不成用户诉求，执行者只能违令或交失败品。

指令层（PRD/任务卡）应当写：

- **要什么结果**：如"Ctrl+Enter 在框内换行且零请求发出；Enter 发送"；
- **不许退化什么**：如"不得丢光标位置、不得破坏原生撤销栈（Ctrl+Z）、不得绕过 React 受控值、
  不得改变请求体字段"；
- **机制留给实现**：换行由浏览器原生（Shift+Enter）还是显式插入（Ctrl+Enter 走
  `document.execCommand('insertText')`，见 `insertLineBreak`）属于实现细节，由实现者按平台真实行为选择，
  但必须满足上一条的不退化清单。

写 PRD 前若对平台默认行为有假设（"浏览器应该会……"），先用最小实验证伪/证实，把结论写进现状事实，
不要写成命令。

## 3. 聊天 composer 一律多行 `<textarea>`（2026-11-23 起）

- 所有聊天输入框（临时需求对话框、待确认卡答复、工作台对话区、员工对话）用 `<textarea rows={1}>` +
  `autoGrow`（约 6 行封顶后内部滚动；发送清空自动收回单行）；单行 `<input>` 物理上无法换行，不再允许。
- 键盘接线只有一种写法：`onKeyDown={e => handleComposerKey(e, send, { multiline: true })}`；
  调用点不得再出现 `multiline: false` 或内联 `e.key === 'Enter'`（静态扫描钉住）。
- placeholder 提示引用 `COMPOSER_HINT` 常量，不得硬编码副本、不得再叠第二份提示。
- 改 composer 不得改变发送语义与请求体（同一端点、同一字段、trim 规则不变）。
