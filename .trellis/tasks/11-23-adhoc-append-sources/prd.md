# 临时需求：追加文档来源 + 笔记双框 + 追加后重编译 Wiki

## 用户诉求（2026-11-23 原话）
「在"临时需求"这个功能里，下面应该有入口给我后面再加文档的。并且比如加"笔记"，要有两个框，一个是这个笔记的主要内容，第二个是这个笔记的原文我给贴上去。」

## 现状事实（已读码核实，不是推测）
- **没有追加入口**：`frontend/src/pages/AdHoc.jsx` 挂的 `KbPanel` 是 `readOnly`，而工作台那套"随时补充 URL/PDF/聊天记录/笔记"的表单（`Supplier.jsx:876-884`，`R3 统一补充入口`）在只读模式下被隐藏 → 用户只能在**创建时**填一次 `docSources`。
- **笔记只有一个框**：`SupplierKbService.java:148-155` 的 `case "chat", "note"` 把 `value` 整段直写 md，`source` 置空，没有"摘要/原文"之分。
- **加完不会刷新 Wiki**：`SupplierKbService` 里搜不到任何"新增来源触发重编译"的路径；日志中「工件更新触发 Wiki 自动重编译」来自**编辑工件**那条路（`SupplierJobService`），与 KB add 无关。所以用户加了文档却问不出效果，属于必然。

## 范围
- **S1 追加入口**：AdHoc 详情页底部一块「继续补充材料」区，能力与工作台补充入口对齐：多行文档来源（URL / 站点 / 本地路径，含 `11-23-kb-office-documents` 新加的 Office 类型）、聊天记录、笔记。复用现成表单写法但**不得让 AdHoc 变成能改工作台行为**（`KbPanel` 若要放开写模式，用参数传入，不改默认值）。
- **S2 笔记双框（用户明确要的两栏）**：
  - 框一 **主要内容**：用户自己的理解/结论/待办（短）。
  - 框二 **原文**：粘贴的原始文本（邮件、群消息、口头约定原话、合同条款…），**原样保存，不改写、不裁剪、不重排空白**。
  - 落库为**一条** KB 条目，md 结构固定为两段：`## 主要内容` + `## 原文`，标题行沿用现网既有格式（`> 笔记（录入于 …）`）以免破坏已入库条目的可比性。原文超长仍走 `DocParser.chunk`。
  - **向后兼容硬要求**：`SupplierKbService.add` 的 note 必须继续支持"只给 `value`"的老调用（工作台、`generated-prompt` 归档 `:994` 等都在用它）——新字段为可选：给了 `summary`/`raw` 走双段，只给 `value` 走老行为。**不许**为了让双框生效去改所有既有调用点。
- **S3 追加后必须让 Wiki 真的更新**：
  - 追加任一来源（文档/笔记/聊天记录）后，自动把该任务的 **WIKI 段重新排队并重编译**，页面显示"Wiki 正在基于新增材料重编译"。
  - 重编译期间**对话照常可用**（这是本项目一贯原则，不许出现"加了材料就锁死对话框"）。
  - **诚实的上下文提示**：只要存在"尚未进入当前 Wiki 版本"的新条目，对话注入的 Wiki 头部必须写明「本任务有 N 条新增材料尚未进入当前 Wiki 版本（正在/待重编译），涉及：…」—— 否则用户会以为模型已经读过他刚粘的原文。
  - 幂等：连点两次追加不得产生重复 KB 条目（沿用现有 `source` 去重语义），也不得排队出两条并发重编译（用现有 `orchestrating` 串行闸）。
- **S4 归属与闸门**：沿用创建者归属与实例独占校验（`RequestIdentity.creator` + `employeeService.assertInstancesUsable`），与 QuickProbe/eval 模块同一套口径，不新造权限模型。

## 验收
- AC1 在已 READY 的临时需求任务里追加一条 URL 来源 → 出现新 KB 条目 → Wiki 自动进入重编译 → 完成后 Wiki 能检索到该来源里的一个固定关键词（断言正文包含关系，不靠人眼看）。
- AC2 记一条笔记：填「主要内容」+ 粘贴 30 行「原文」→ KB md 里两段齐全、**原文逐字一致**（含换行与空白，做等值断言而非"包含"）。
- AC3 只填原文不填主要内容 → 仍成功入库（另一段留空标记）；只填主要内容 → 成功；两个都空 → 可读错误。
- AC4 向后兼容：工作台"补充笔记"（单框老形态）行为不变，`generated-prompt` 归档不变，`SupplierKbServiceTest` 既有用例零改动通过。
- AC5 重编译期间发消息能立刻得到回应，且注入头里出现"新增材料尚未进入当前 Wiki 版本"的提示。
- AC6 连续追加 3 次不产生重复条目、不产生两条并行重编译任务。
- AC7 会话红线不变：不 `steer` / 不 `chatInLane` / 不 `abortCurrentTurn`；与飞书共用实例主会话。
- AC8 全量 `mvn test` 红数不超过届时基线（当前 `359 跑 / 3 失败 / 1 错误 / 1 跳过`，4 条为既有 Windows 伪脚本与 temp 环境红）；`npm run build` 通过但**不写** `src/main/resources/static/`（发布归主会话）。

## 明确不做
不做附件上传（走"给路径/URL"的既有口径）；不做笔记编辑历史/版本；不做富文本编辑器；不加权限模型；不把 AdHoc 与工作台的补充表单合并成一个组件（避免耦合，允许结构相似）。

## 并入本棒的另两件（同改 `AdHoc.jsx`/`Supplier.jsx`，拆开跑只会互相等）
- **X1 聊天输入框从 `<input>` 改为可换行的多行框**：用户诉求是字面的「所有的输入框都要支持 Ctrl+Enter 换行」，但键盘那棒实测证明单行 `<input>` 物理上无法换行，只能做到"不误发"。受影响：**临时需求对话框**、**待确认卡答复框**、**工作台对话区**、**员工对话**（4 处单行 composer）。要求：改 `<textarea>` 自适应高度（1→最多 ~6 行滚动）、沿用 `handleComposerKey(..., {multiline:true})` 与 `COMPOSER_HINT` 文案、发送后清空并回到单行高度、**不得改变发送语义与请求体**（同一接口同一字段）。AC：四处 Ctrl+Enter 真换行且零多余请求，Enter 仍发送；Playwright 复跑。
- **X2 让 Playwright 套件可复现**：本仓 `playwright.config.js:5` 的 `baseURL` 默认是 `http://feishu-ui-preview:5173`（别的环境域名），且本机 `ms-playwright` 目录为空 —— 主会话必须靠手动设 `CHROMIUM_PATH="C:/Program Files/Google/Chrome/Application/chrome.exe"` + `UI_BASE_URL=http://localhost:3000` 才能跑出 5 passed。要求：默认值改为 `http://localhost:5173` 之类的本地地址、加 `webServer` 自动起 vite（已有 dev server 在 3000 时能复用/不冲突）、`CHROMIUM_PATH` 缺省时自动探测已安装的 Chrome/Edge 路径，并在 `frontend/README` 或测试文件头写清一行调用命令。目标：**我下次 `npx playwright test` 不需要任何魔法环境变量**。
- **X3 spec 追加两条**（进 `.trellis/spec/frontend/`，无则新建 `composer-and-events.md`）：
  ① **React 合成键盘事件不透传 `isComposing`**（`react-dom` 的 `KeyboardEventInterface` 无该字段），因此判输入法组字**必须**用 `e.nativeEvent.isComposing`（或 `keyCode===229`）兜底 —— 只写 `e.isComposing` 会在组字态 keyCode=13 时把半成品发出去（已实测 POST 1→2）。
  ② **不要让 PRD 写死与平台行为冲突的实现机制**：上一棒 PRD 命令"不许自己插换行、靠不拦截"，而浏览器对 Ctrl+Enter 本就没有插行默认行为 —— 指令层写"要什么结果 + 不许退化什么（光标/撤销/受控值）"，机制留给实现。

## 依赖与排序

**必须排在 `11-23-kb-office-documents` 之后**：两者都改 `SupplierKbService.add` 与 `detectType` 一族，并行会互相覆盖。完成后由主会话统一 `npm run build` → `mvn package` → `scripts/run-backend-detached.ps1` 重启发布。
