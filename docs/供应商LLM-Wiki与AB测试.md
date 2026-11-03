# 供应商 LLM Wiki 与纯文本对照

创建 API 供应商工作时，第一步选择 **TEXT 纯文本**或 **WIKI**。历史工作默认 TEXT；创建后模式固定。需要对照时，针对同一个供应商、同一批材料分别创建两份工作。

两种模式保留同一个原始知识库：接口文档、真实接口请求与返回、商务沟通等仍按原来的方式添加。TEXT 沿用原有按阶段交付文本的方式；WIKI 增加由模型编译、带目录、链接、引用和阶段入口的知识视图，不增加向量数据库。

## 使用流程

1. 启动 OrbStack，运行 `python3 scripts/database.py up`，打开 http://localhost:18080。
2. 创建工作并选择 WIKI，绑定已启用、已配置 Claude 登录及所需 MCP 的执行实例。
3. 添加材料，等待所有需要编译的材料解析完成。网站材料会读取已加载的子页面全文。
4. 在 Wiki 面板按需填写该供应商的补充编译规则，点击编译。可以查看进度、错误、目录、页面、原文证据和反向链接。
5. READY 后执行工作流。材料或编译规则变化后会显示 STALE，需要重新编译；新增/变化的来源重新提取，未变来源复用缓存。

编译需要模型调用。新建的本地容器不会自动继承另一台电脑的 Claude 登录和实例配置。未绑定实例会明确报错；编译失败或过期会阻止 Wiki 工作执行，不会偷偷改用纯文本。

## 如何让材料在需要时到达 Agent

编译分为完整来源提取与跨来源页面整理。模型返回草稿 JSON，应用检查来源覆盖、逐字证据引用、链接和阶段入口后，才写入不可变版本并发布。

每次执行前，程序根据当前阶段选出全部关键约束页和该阶段入口页，把正文直接加入实际请求，并提供分类目录、其他页面与完整原文的固定版本路径。必读上下文超过 32000 字符时明确失败，要求整理入口页；不会截断。额外细节可沿链接读取。

这保证了必读材料被程序交付，其他材料有稳定的导航和证据入口。它不能保证模型理解正确、主动读完所有补充页，或绝不通过通用工具访问其他文件。已有真实接口执行证据、公司 spec、Skills、提示词生成及 Trellis 门禁继续生效。

## 数据在哪里

| 内容 | 存放位置 |
| --- | --- |
| 工作、模式、来源索引、版本状态、执行记录 | OrbStack MySQL 的 `feishu_claude` 数据库（默认配置） |
| 原始知识库文本 | 后端 `/app/data/supplier-kb`，保存在应用数据 volume |
| Wiki 派生数据 | `/app/data/supplier-kb/.wiki/<供应商编码哈希前24位>/` |
| 编译规则、日志、提取缓存 | 上述目录的 `instructions.md`、`log.md`、`extractions/` |
| 固定版本原文、Wiki 页面、目录与清单 | `revisions/<版本ID>/raw/`、`wiki/`、`schema.md`、`manifest.json` |

数据库记录版本发布状态和文件哈希；Markdown 文件保存原文与页面正文。读取接口只接受本供应商、本版本登记的页面/来源 ID，并校验内容哈希。历史版本原文可以继续用于追溯，不会被新编译覆盖。跨电脑携带需要同时备份数据库和应用数据，使用 [MySQL 本地部署与迁移](./MySQL本地部署与迁移.md) 中的脚本。

## 如何做 A/B 测试

使用相同供应商编码、来源指纹、工作流模板、阶段及模型配置，创建 TEXT 与 WIKI 工作。会话按工作、模式、实例隔离。面板显示最近 200 条执行记录：材料字符数、请求字符数、实际输入/输出 token、缓存读写 token、状态与耗时。未知 token 显示未知；字符数不冒充 token。

Wiki 编译成本单独显示。比较时还要人工检查产出的正确性、约束遗漏、接口实测覆盖与返工次数；不能仅按上下文长度判断效果。目前自动测试使用模型桩验证编译协议与门禁，尚未完成真实供应商材料上的模型质量对照。

## GitHub 调研与验证

实现前通过 GitHub MCP 阅读了以下项目的实际代码，借鉴受控发布、引用验证和读取边界：

- [atomicstrata/llm-wiki-compiler](https://github.com/atomicstrata/llm-wiki-compiler)：派生知识与发布流程。
- [AgriciDaniel/claude-obsidian](https://github.com/AgriciDaniel/claude-obsidian)：模型产出草稿、程序负责写入与前置校验。
- [Astro-Han/karpathy-llm-wiki](https://github.com/Astro-Han/karpathy-llm-wiki)：原文证据检查与知识整理分离。

具体审阅的提交和文件见 Trellis 任务研究记录。实现适配当前 Java/MySQL/Claude 会话系统，没有引入这些项目的向量检索依赖。

可重复验证（依赖运行中的 OrbStack MySQL）：

```sh
docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9-eclipse-temurin-21 mvn -q -s scripts/maven-settings.xml test
python3 scripts/tests/run_wiki_mysql.py
python3 -m unittest scripts.tests.test_stack_smoke -v
```

MySQL 集成测试使用临时独立数据库并自动清理，验证真实持久化、版本文件和 HTTP 读取；模型调用使用测试桩，不会消耗真实模型额度。
