# 全站输入框键盘约定：Ctrl+Enter 换行、不误发

## 用户诉求（2026-11-23 原话）
「所有的输入框都要支持 Ctrl + Enter 能在框里换行而不是发送」

## 现状（已实测统计，不是猜）
```
有 onKeyDown 的位置：Supplier.jsx ×4、AdHoc.jsx ×2、Employees.jsx ×1、E2ETest.jsx ×1（共 8 处）
写法统计： e.key === 'Enter' && !e.shiftKey  ×5   |   e.key === 'Enter'  ×3
→ 结论：Ctrl+Enter 现在会**发送**（没有被排除）；那 3 处裸 Enter 判定连 Shift+Enter 都无法换行。
```

## 规则（一处定义，全站一致）
- **新建** `frontend/src/utils/composerKeys.js`，导出 `isSendKey(e, { multiline })`：
  - 多行（textarea）：`Enter` 且 **无** Shift / Ctrl / Alt / Meta → 发送；否则**不拦截**。
  - 单行（input）：`Enter` 且 无 Ctrl / Alt / Meta → 提交（单行本来无法换行，不得因为 Ctrl+Enter 触发提交）。
  - **输入法组字期间一律不发送**：`e.isComposing === true`（或 `e.keyCode === 229` 兜底）时直接返回 false。**这是本任务最容易漏的一条**——用户全程中文输入，组字回车把半成品发出去是他每天都会撞的事故，优先级高于 Ctrl+Enter 本身。
- **实现方式必须是"不 preventDefault"**：判定为"不发送"时什么都不做，让浏览器原生插入换行。**禁止**自己拼 `\n` 改 `value`（会丢光标位置、丢撤销栈、与 React 受控值打架）。
- 8 处调用点全部改为 helper；调用点**不得再残留任何内联 `e.key === 'Enter'` 判定**。
- 提示文案统一为「Enter 发送 · Shift/Ctrl+Enter 换行」（放 placeholder 或 title，二者择一，别叠加重复提示）。单行输入框不加这句（它没法换行）。文案须过项目措辞禁词纪律（无游戏类说辞）。

## 验收
- **AC1** 临时需求对话框：敲 `Ctrl+Enter` → 框内出现换行、**没有发出任何请求**（断言无 POST）；`Shift+Enter` 同；`Enter` → 正常发出。
- **AC2** 工作台对话区、DevChat、以及（若已存在）笔记的「主要内容 / 原文」两个框，行为与 AC1 一致。
- **AC3 输入法**：组字状态下按 Enter（`isComposing=true`）不得发送、不得清空输入（Playwright 用 `keydown` + `isComposing` 或 `k:composer` 事件模拟；模拟不了就在回报里如实标"未验证"并给出单测级的 `isSendKey` 断言）。
- **AC4** 单行 input（搜索框、路径框、名称框）：`Ctrl+Enter` 不提交、不报错、不产生多余请求。
- **AC5 防回归**：一条静态扫描测试断言 `frontend/src/**/*.jsx` 中不再出现内联 `e.key === 'Enter'`（helper 文件自身除外）——这样以后新加的输入框不会又各写一套。
- **AC6** `npx vite build` 通过；**不要**写 `src/main/resources/static/`（发布归主会话）。

## 明确不做
不改后端、不改按键以外的交互逻辑；不引入新组件库/新依赖；不做"自定义快捷键配置"界面（全站固定这一套约定即可）。

## 执行限制（重要，与并行任务共处）
当前有另一棒正在实现 `11-23-kb-office-documents`（Java 侧 Office 解析），构建窗口归它。本任务**纯前端**：
- **禁止运行 `mvn` / 读写 `target/`**；验证一律用 `npx vite build --outDir target/tmp-composer --emptyOutDir`（跑完删）与 Playwright。
- `Supplier.jsx` / `AdHoc.jsx` 只允许改**键盘处理与提示文案相关行**，不得顺手重构别处；如与 Office 那棒的前端提示改动撞上，以最小改动让路并照实回报。
- 禁止 `git stash` / `checkout` / `reset` / `commit`；8080 上那个独立后端进程（PID 见 `D:/feishu-claude-data/run/backend.pid`）不许动。
