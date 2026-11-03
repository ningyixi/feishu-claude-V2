# OrbStack 本地部署、迁移与换电脑

本页命令依赖 OrbStack/Docker。Windows 主力机没有 Docker、仍使用旧 H2 的情况，请先读 [Windows 无 Docker 迁移交接](./Windows无Docker迁移交接.md)，不要直接套用下方容器命令。跨 Windows/Linux 的 Flowable 表名大小写兼容仍需单独验证。

## 一键启动

前端、后端和 MySQL 全部在容器中。宿主机只需 OrbStack/Docker 与 Python 3；不依赖宿主机的 Java、Node 或 Maven。

```sh
python3 scripts/database.py init
python3 scripts/database.py up
```

首次自动构建。默认地址：前端 http://localhost:18080 ，后端 http://localhost:18081 ，MySQL localhost:3307 。Compose 项目名为 feishu-claude，与 workdesk 完全隔离。

生成的 `.env.mysql` 是本机私有配置，含随机密码，不提交 Git。不要在已有卷上重新生成密码；`init` 不会覆盖它。端口和 `WORKSPACE_ROOT` 可在该文件修改。`WORKSPACE_ROOT` 默认本仓库目录，在后端映射为 `/workspace`；其他供应商代码、Skill/spec 应放在这个目录下或修改挂载根目录。容器内请配置 `/workspace/...` 路径，不要填宿主机 Windows/Mac 路径。

后端内置 Java 21、Python 3、Node 22、Git 和固定版本 Claude CLI。以宿主机对应 UID/GID 的非 root 用户运行。Claude 登录和 MCP 配置在容器自己的 home 中，不会自动读取宿主机配置：

```sh
docker compose -p feishu-claude --env-file .env.mysql exec backend claude auth login
docker compose -p feishu-claude --env-file .env.mysql exec backend claude mcp list
```

登录及 Postman MCP 仍需按你的账号配置；服务启动成功不代表已经登录 Claude。

## 数据持久化

- `feishu-claude_mysql_data`：MySQL 数据目录。
- `feishu-claude_app_data`：供应商知识库、解析文件、交付快照，容器路径 `/app/data`。
- `feishu-claude_claude_home`：Claude 配置及会话文件，容器路径 `/home/node`。
- `/workspace`：绑定到宿主机的代码仓库，Trellis 任务也在此，不包含在数据备份包中，需通过 Git/代码目录自行转移。

`python3 scripts/database.py down` 保留数据卷。不要执行 `docker compose down -v`，除非明确要删除数据。

## H2 旧数据迁移

旧 H2 文件目前在另一台电脑，尚未迁入这里。迁移工具已经提供，不会凭空创建 H2 源文件，也不会覆盖现有目标数据。

先关闭旧 H2 应用，再把 `config.mv.db` 放到可访问的本机目录。新机器首次只启动 MySQL；若已经启动过后端产生表，可以选用一个全新的数据库和数据卷，旧存储保留：

```sh
python3 scripts/database.py init
docker compose -p feishu-claude --env-file .env.mysql up -d --wait mysql
python3 scripts/database.py new-store --name feishu_import_20261103
python3 scripts/database.py migrate-h2 --source /path/to/config.mv.db
```

`new-store` 会停止前后端、创建独立空库并切换本机配置，不删除旧库或卷。不要在迁移前启动新存储的后端，否则自动建表后会被迁移器拒绝。

迁移使用 JDBC 读取源 PUBLIC 表，转换为 MySQL，保留原始主键、空值、Unicode、LOB、默认值、唯一/普通索引、外键和自增安全续号。源库只读，先保留二进制备份；每张表逐行计算内容指纹并独立回读校验，成功才产生 `status: verified` 报告。报告及源备份在 `backups/h2-migration-.../`。

目标必须为空；未知类型、视图、自定义 schema、超出 MySQL 精度的数值或内容不一致会失败，绝不跳过后宣称完整成功。失败后保留现场，使用新的空存储重试；MySQL DDL 不能整体事务回滚。H2 1.x 旧格式需先用对应旧驱动导出/升级，本工具使用当前项目 H2 驱动。

知识库正文不在 H2 文件里：还需转移旧 `supplier-kb` 文件夹，并把数据库 `supplier_kb_entry.md_path`、`supplier_kb_page.md_path` 的旧目录前缀映射到 `/app/data/supplier-kb`。站点 `index.md` 中的页面路径也需要转换。不要重写 `.deliveries` 中的历史原始快照；代码路径和 Skill/spec 路径应在 UI 中改成 `/workspace/...`。只有拿到旧源目录后才能验证这些外部文件的完整性；数据库报告不代表文件也已迁完。

## 多台电脑，各自用本地 MySQL

这些数据库不会随 Git 自动同步，也不会自动合并两台电脑各自的新数据。使用备份包搬运同一份快照；避免两台机器同时编辑后期待无冲突合并。

电脑 A：

```sh
python3 scripts/database.py backup --output /path/to/feishu-backup.zip
python3 scripts/database.py up
```

备份会先停止前后端，保持 SQL 与文件一致；包含 SQL、应用文件、Claude home、SHA-256 清单和每表行数。临时 CLI debug/cache 不包含。包中可能有业务数据和认证信息，应自行妥善保管，不提交 Git。

电脑 B 拉取代码，安装 OrbStack/Docker 后：

```sh
python3 scripts/database.py init
python3 scripts/database.py build
docker compose -p feishu-claude --env-file .env.mysql up -d --wait mysql
python3 scripts/database.py new-store --name feishu_restore_20261103
python3 scripts/database.py restore /path/to/feishu-backup.zip
python3 scripts/database.py up
```

恢复会校验包成员、文件校验和、归档路径及导入后的每表行数；拒绝覆盖非空数据库。容器内部数据路径固定，所以使用这套容器生成的备份在换电脑后不需要改知识库路径。代码仓库、外部工作区、Git/模型账号授权可能仍需分别配置。

## 兼容原 H2 启动

默认 profile 已改为 mysql。旧 H2 模式仅用于兼容或导出：`--spring.profiles.active=h2`，路径仍由 `FEISHU_DB_PATH` 控制。不要用空的 H2 启动结果代替对实际旧数据的迁移验证。
