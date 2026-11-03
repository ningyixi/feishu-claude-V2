# Windows 主力机无 Docker：H2 → MySQL 迁移交接

这份文档供你在 Windows 主力机拉取代码后先核对环境与数据，**当前不是已验收的 Windows 一键迁移包**。截至 2026-11-04，Windows 的真实 H2 文件和知识库目录尚未拿到，未执行该机器的数据迁移。

## 1. 明天先做什么

1. 记录 Windows 当前能正常运行的代码版本、启动方式、数据库和知识库实际目录。
2. 退出旧程序及可能持有 H2 文件的其他 Java 进程，再备份完整数据目录。
3. 确认备份可读后拉取新版代码；先阅读本页，**不要直接启动新版连接旧 H2**。
4. 核对第 3、4 节的信息，确认原生迁移入口和兼容检查已经补齐，再实施第 5 节。

迁移以 **Windows 主力机的旧数据**为准。Mac 这边的开发/验证数据库不覆盖 Windows；两边各自新增的数据也不会自动合并。Git 同步代码，不同步数据库、知识库材料或账号登录。

## 2. Windows 需要哪些部件

| 部件 | 是否需要独立服务 | Windows 无 Docker 的方式 |
| --- | --- | --- |
| Java 应用 | 是 | Java 21；源码构建/Java 迁移工具使用 JDK 21 |
| 前端 | 不必另开服务 | 生产静态文件已打进后端 JAR，Spring Boot 可提供页面与 API |
| MySQL | 是 | 本机 MySQL 8.4，作为 Windows 服务运行；无需 Docker |
| Flowable | 否 | 已嵌入 Java 应用，流程与任务表在同一 MySQL 中 |
| LLM Wiki | 否 | Java 编译服务、本地文件和同一数据库中的版本记录 |
| Claude CLI、Git、MCP | 按现有实例需要 | 保留 Windows 原有工具和授权，不用 Mac 容器的 home 覆盖 |
| Maven | 仅构建/准备 Java 依赖时需要 | 使用仓库内 `scripts/maven-settings.xml`，已配置阿里云镜像 |
| Node.js | 修改/构建前端或现有 CLI 安装需要时使用 | 运行已打包前端不必启动 Node 前端服务 |
| Python | 取决于后续运维入口 | 现有 `scripts/database.py` 调用 Docker，安装 Python 本身不能使它原生运行 |

这里没有新增 Redis、消息队列、向量数据库或独立图数据库。安装方式参考 [MySQL 官方 Windows 安装文档](https://dev.mysql.com/doc/refman/8.4/en/windows-installation.html)。

## 3. 需要确认和备份的内容

| 内容 | 应确认的信息 | 为什么要保留 |
| --- | --- | --- |
| 旧代码/启动参数 | 当前 Git 提交、JAR、profile、环境变量 | 回退时旧程序与旧库要匹配 |
| H2 | `config.mv.db` 实际位置、驱动版本、用户名和是否有密码 | 新旧 H2 文件格式可能不同 |
| 知识库目录 | 实际 `FEISHU_KB_PATH` 或默认目录 | 正文、子页面、历史快照不全在数据库里 |
| 外部原文件 | PDF/Markdown 原件是否在知识库目录外 | 数据库可能仅保存其磁盘路径 |
| 工作区 | 代码仓库、分支、未提交改动、Trellis 工作目录 | 不随 SQL 数据迁移 |
| Skill/spec | 目录、入口文件、生成提示词 Skill 的路径 | Agent 需要真实可读的文件 |
| CLI/MCP/Git | Windows 原有 home、相关配置和授权是否仍可用 | 数据库记录不等于工具已登录 |
| 新 MySQL | 版本、端口、账号、权限、空库名、表名大小写设置 | 不使用 Mac 的容器地址和密码 |
| 实际使用情况 | 是否已有新版 Wiki/Flowable 数据、运行中任务 | 决定是否需要额外处理版本目录及引擎表 |

数据库和文件应在应用停止后作为同一份备份保存。不要把 H2、密码、认证配置和真实业务材料提交 Git。知识库文件建议用 ZIP 等方式按原字节保存；不要用换行转换或全文替换改写带哈希的历史证据。

可先做只读环境确认，路径按实际情况填写：

```powershell
java -version
Get-Command java,mvn,mysql -ErrorAction SilentlyContinue
Get-Item 'D:\feishu-claude-data\config.mv.db'
Get-FileHash 'D:\feishu-claude-data\config.mv.db' -Algorithm SHA256
```

检查文件存在与哈希不等于数据库已迁移；备份后再核对旧驱动版本。默认目录只是代码中的回退配置，不代表你的机器一定使用这个位置。

## 4. 现有工具具备什么，还缺什么

### 已有

- MySQL 默认 profile；显式 `h2` 兼容 profile。
- `scripts/H2ToMysql.java`：JDBC 读取 H2，写入空 MySQL；保留 ID、长文本、索引、外键、自增续号，逐表核对行内容，保留源文件副本与 `migration-report.json`。
- 源连接必须包含 `IFEXISTS=TRUE`、`ACCESS_MODE_DATA=r`，避免创建新空 H2 或写入源库。
- `scripts/database.py`：OrbStack/Docker 环境的启动、迁移、备份和恢复；容器路径固定。
- `SpaForwardController`：后端可托管前端，并处理 `/supplier` 等 SPA 路由刷新。

### 尚需在 Windows 原生环境补齐/验证

- 无 Docker 的启动、迁移、备份/恢复入口与环境检查。
- 当前真实 H2 驱动版本、表结构、特殊字段与原数据的迁移演练。
- 文件完整性检查、数据库内路径和文件内必要导航路径的可审计映射。
- MySQL/Flowable 表名大小写、重启、任务历史与知识库读取的完整回归。

Java 转换核心不依赖 Docker，但现成 Python 启动器依赖 Docker；不能把“已有 Java 核心”当成“Windows 一键迁移已验收”。原生调用需要准备运行依赖，并提供 `SOURCE_URL`、`SOURCE_FILE`、`SOURCE_USER`、`SOURCE_PASSWORD`、`TARGET_URL`、`TARGET_USER`、`TARGET_PASSWORD`、`REPORT_DIR`。凭证不要写进仓库或提交记录。

### 特别检查：Flowable 与跨系统表名

当前迁移核心会把来源表名转成小写。如果源 H2 已有 Flowable 的 `ACT_*` 表，需要先验证大小写与引擎版本兼容，不能直接套用普通业务表迁移结论。尚未使用新版流程的旧 H2 则应在业务数据导入后，由新版在目标库初始化所需引擎表。

MySQL 的 `lower_case_table_names` 默认值随平台不同，且不能在已经初始化的数据目录上随意改值。因此，Windows 原生库与 Mac 上的 Linux 容器库之间往返搬运，尤其涉及 Flowable 表时，需要专门演练；普通备份成功或行数一致不能证明引擎可用。见 [MySQL 官方变量说明](https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html#sysvar_lower_case_table_names)。本交接不直接修改任何现有 MySQL 的该设置。

## 5. 正式迁移顺序

1. **冻结和备份来源。** 停止 Windows 旧应用，备份数据库、知识库及外部文件；记录旧版本与源文件哈希。
2. **准备原生 MySQL 空库。** 使用独立迁移库，不覆盖其他项目；新版后端尚不启动，避免自动建表占用目标空库。
3. **确认兼容并演练。** 在隔离的空库上验证源 H2 版本、表名、字段类型与迁移工具。源格式不兼容时，先用匹配的旧驱动制定导出方案，不直接写原文件升级。
4. **迁移数据库。** 使用经过验证的原生入口，保留源副本与迁移报告。要求报告 `status: verified`，核对逐表行数、内容指纹、约束与自增续号。
5. **迁移文件并映射路径。** Windows 同机迁移优先保留原知识库位置；确需换位置时，按第 6 节处理，保留变更清单。
6. **启动新版验收。** 指向目标 MySQL 和真实文件目录，检查实例、员工、任务、知识库、附件与流程。需要执行外部任务时再确认 CLI/MCP 授权与上下文。
7. **验收通过后切换主用。** 旧 H2、旧代码与原备份继续保留。若失败，不把新库半成品当成已成功；记录问题并换新的空库重试。

MySQL DDL 不能整体事务回滚。失败现场可能已有部分表；迁移器拒绝向非空库覆盖/合并。回退应停止新版、用匹配旧版本连接保留的旧 H2 和文件备份；新库迁移后产生的记录不会自动反向合并。

## 6. 文件与路径不能漏

只复制 H2 或导入 SQL 都不够，至少检查：

- `supplier_kb_entry.md_path`、`supplier_kb_page.md_path`，以及来源指向的 PDF/Markdown 原件。
- 站点索引中的导航路径、知识库的完整子目录。
- `.deliveries` 等历史交付快照：保留原始内容，不全局替换历史证据正文。
- 如果源端已经有 Wiki：完整 `.wiki` 目录与数据库版本记录一起保留；核对 `supplier_wiki_revision.directory`。移动根目录后旧绝对路径需要明确处理。
- 如果源端已有工作流上传：完整 `.workflow-uploads` 与 `supplier_workflow_attachment` 记录一起保留，检查附件路径。
- 实例工作目录、供应商类库、Skill/spec/提示词 Skill 的路径。

原有 Windows 路径可继续使用；不要改成容器专用的 `/app/data`、`/workspace` 或 `mysql:3306`。路径映射是在数据库内容迁移验证之后的独立步骤，需要记录预期变更并验证文件可读；不能用“原始行哈希全相等”代替映射后的验收。

## 7. 迁移完成后的原生启动示例

**以下仅用于迁移验收完成后启动，不是迁移命令。** 需要已构建的 JAR、Java 21、运行中的本机 MySQL 及真实目录；密码通过本机凭证输入，不写入 Git。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:FEISHU_MYSQL_URL = 'jdbc:mysql://localhost:3306/feishu_claude?characterEncoding=UTF-8&connectionTimeZone=UTC&preserveInstants=true'
$credential = Get-Credential -UserName 'feishu' -Message '输入已创建的本机 MySQL 用户凭证'
$env:FEISHU_MYSQL_USER = $credential.UserName
$env:FEISHU_MYSQL_PASSWORD = $credential.GetNetworkCredential().Password
$env:FEISHU_KB_PATH = 'D:/feishu-claude-data/supplier-kb'
java -jar target/feishu-claude-v2-1.0-SNAPSHOT.jar
```

库名、端口、账号、目录均按实际修改；示例库和用户不会由该启动命令自动创建。默认访问 `http://localhost:8080`。首次源码构建可使用 `mvn -s scripts/maven-settings.xml -DskipTests package`，仓库已带构建后的前端；修改前端源码后才需要重新执行前端构建。

旧 H2 profile 通过 `--spring.profiles.active=h2` 显式选择，路径由 `FEISHU_DB_PATH` 控制，值不带 `.mv.db` 后缀。这是兼容配置，不表示新版全部功能已在你的 Windows/H2 数据上验证。新版有自动建表/更新逻辑，先备份与演练，再决定是否连接旧文件。

## 8. 验收与多电脑使用

验收不止看首页能否打开：

- [ ] 迁移报告 verified，逐表记录与关键中文长文本正常，旧库未被修改。
- [ ] 知识库正文、站点子页面、原件和附件可打开；路径映射有记录。
- [ ] 实例、员工、供应商工作、节点及历史记录保留。
- [ ] 如原来已有 Wiki/工作流：固定版本证据、人工任务、历史和重启恢复通过验证。
- [ ] TEXT/WIKI 选择、公司 spec/Skill/Trellis 与真实接口证据门禁仍生效。
- [ ] Windows 本地 CLI/MCP 能按预期工作，再执行真实任务。
- [ ] 关闭并重新启动后数据仍存在，原生备份/恢复在独立空库演练通过。

主力机先稳定用同一份本地 MySQL。跨电脑使用时传输数据库与文件的一致快照，明确哪台机器是主写入端；不要同时修改后依赖 Git 或 SQL 导入自动合并。

相关说明：[现有容器迁移工具](./MySQL本地部署与迁移.md)、[LLM Wiki 存储架构](./LLM-Wiki存储架构说明.md)。
