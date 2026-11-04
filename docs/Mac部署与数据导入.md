# Mac 部署与数据导入

目标：让 Mac 也能跑这个项目，并且**不需要两台机器来回同步**。做法 = 从 Windows 导出一份**完整种子包**（库 + 知识库文件），Mac 一次性导入成自己的独立本地库；之后两台各用各的。

- **Windows 是主开发/使用机**（权威数据在此）。
- **Mac 是完整副本**：可独立浏览/演示/继续开发；不依赖 Windows。
- 两台数据库**不会自动合并**——真要长期各改各的，再考虑合并策略；日常"Mac 能直接起来用"按本文即可。

---

## 0. 前置（Mac 一次性）

1. 拉代码：`git clone` 或 `git pull`（本仓库）。
2. 装一个 MySQL 8（二选一）：
   - **原生**（推荐，和 Windows 对称、最省事）：`brew install mysql@8.4 && brew services start mysql@8.4`
   - 或 **OrbStack/Docker 全栈**：见 `docs/MySQL本地部署与迁移.md`（`python3 scripts/database.py init && up`，自带 MySQL:3307）。注意该容器栈是它自己的一套路径与卷，导入种子时把 `MAC_DB_*`/端口指到容器 MySQL 即可。
3. 记住你 Mac 上 MySQL 的 root（或建一个 `feishu` 用户）密码，下面会用到。

> JDK 21、Node 20 仅在“Mac 上从源码构建/改前端”时才需要；只是导入+跑现成 jar 的话，装好 MySQL 就够（jar 自带前端静态页）。

---

## 1. 在 Windows 上导出种子包

在项目根目录（Git-Bash）：

```bash
bash scripts/export-mac-seed.sh
```

产物：`backups/mac-seed-<时间戳>.tar.gz`（约几百 KB～几 MB）。内含：
- `feishu_claude.sql`：当前生产库**完整结构 + 数据**（业务表 + 30 张 Flowable 引擎表，保留主键/空值/中文长文本/索引/自增）
- `supplier-kb.tar.gz`：全部知识库正文/子页面文件
- `import-mac-seed.sh`：Mac 端导入脚本
- 本文档

把这个 `.tar.gz` 用 AirDrop / 网盘 / `scp` 任一方式拷到 Mac。（`backups/` 已在 `.gitignore`，数据不进 Git。）

---

## 2. 在 Mac 上导入并启动（原生路径）

```bash
mkdir macseed && tar -xzf mac-seed-*.tar.gz -C macseed --strip-components=1 && cd macseed
export MYSQL_PWD='<你Mac的MySQL密码>'
# 可选覆盖（默认值如下）：
#   MAC_DB=feishu_claude  MAC_DB_USER=feishu  MAC_KB_DIR=~/feishu-claude-data/supplier-kb
bash import-mac-seed.sh
```

脚本会：建库 → 导入 59 表结构+数据 → 解出知识库文件 → **把库里 `D:\feishu-claude-data\supplier-kb` 路径自动改写成 Mac 的 `MAC_KB_DIR`**（否则正文打不开）。

然后按脚本结尾打印的环境变量启动（示意）：

```bash
cd <项目目录>       # 用刚才拉取的仓库；jar 见下方构建，或指向已构建 jar
export SPRING_PROFILES_ACTIVE=mysql
export FEISHU_MYSQL_URL='jdbc:mysql://localhost:3306/feishu_claude?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
export FEISHU_MYSQL_USER='feishu'          # 或你在导入脚本里用的用户
export FEISHU_MYSQL_PASSWORD="$MYSQL_PWD"
export FEISHU_KB_PATH="$HOME/feishu-claude-data/supplier-kb"
# 若 Mac 上还没构建 jar：先 mvn -s scripts/maven-settings.xml -DskipTests package
java -jar target/feishu-claude-v2-1.0-SNAPSHOT.jar
```

浏览器打开 **http://localhost:8080** —— 数据即与 Windows 当时那份一致，之后可独立用。

> 前端：生产静态页已打进 jar，后端直接托管，**Mac 不用单独起 Node**。要边改前端边看才 `cd frontend && npm install && npm run dev`（默认 http://localhost:3000 ，已配 `/api` 代理）。

---

## 3. 不用种子也能起（Mac 全新空库）

Mac 若只是要一个能跑的实例、不需要这份数据：只要指向一个**空的 MySQL 库**并启动，应用会**自建全部业务表 + Flowable 引擎表**（实测约 24 秒），返回空数据、功能正常。即 Mac 完全可以“裸跑”。

---

## 4. 已知边界（诚实说明）

- **Windows 专属路径**：种子里实例的 `工作目录`、供应商类库、Skill/spec 路径仍是 `D:\...`/`Z:\...`。仅浏览/演示无影响；要在 Mac 真正让 agent 跑任务，去“实例管理/Skill 维护”里把这些路径改成 Mac 本地路径即可。
- **表名大小写**：Windows 本机 MySQL `lower_case_table_names=1`；种子导出表名已是小写，Mac(Linux) 默认 0 也能正常导入使用。仅在你把两台库做“来回往返”且涉及 Flowable 表时才需专门演练——本方案是单向一次性导入，不受影响。
- **两台不同步**：导入是一次性快照。之后 Windows 的新数据不会自动到 Mac，反之亦然。需要再拿最新，就在 Windows 重跑第 1 步、Mac 重导一遍（覆盖式，简单可靠）。
- 敏感：种子包含真实业务数据，只走你自己的传输通道，勿提交 Git、勿外发。

---

## 5. 相关脚本/文档

- `scripts/export-mac-seed.sh`（Windows 导出）
- `scripts/import-mac-seed.sh`（Mac 导入 + 路径改写）
- `scripts/run-local-mysql.sh`（Windows 本机 mysql 模式一键起后端）
- `docs/MySQL本地部署与迁移.md`（OrbStack 容器全栈）
- `docs/Windows无Docker迁移交接.md`（H2→MySQL 原始迁移背景）
