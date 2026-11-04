# Wiki 编译并行化：发布分批并发执行（+抽取并行二期）

## 背景
发布已分批（每批 ≤3 源、独立 lane、失败跳批）。批与批之间**无依赖**（各自只读 briefIds/facts，产出 pages 由程序按批序合并）→ 天然可并行。当前串行：8 批 × 每批 1-3 分钟 = 10-25 分钟，是"编译慢/疑似卡住"体感的主因。

## 调研结论（2026-11-04）
- `ClaudeCodeService.lanes` = ConcurrentHashMap<lane, TerminalSession>；不同 lane 独立 CLI 进程，`chatInLane` 跨 lane 可并发（getOrCreateLane synchronized 只锁进程创建）。
- 约束①：`LANE_IDLE_CAP=6`，创建新 lane 时驱逐**非 busy** 的最旧 lane → 并发 ≤3 且 wiki lane 都在 busy，安全。
- 约束②：`JsonArray`/`LinkedHashSet` 合并与 `revisions.save(row)`（心跳/phase）非线程安全 → 各批结果先收集（ConcurrentLinkedQueue / Future 列表），**主线程按批序合并**；phase 心跳写入加锁。
- 约束③：模型网关并发/限流未知 → 信号量限并发（默认 3，`-Dwiki.publishParallelism` 可调），批内已有 2 次重试兜底。
- 约束④：每 lane 一个 CLI 进程（数百 MB）→ 并发 3 上限合理；虚拟线程等待 IO 零成本。

## 设计
1. `synthesizeInBatches` 改并行：虚拟线程 ExecutorService + `Semaphore(par)`；每批任务=2 次尝试的现有逻辑；结果 `BatchResult{bi, out|null}` 收集到按 bi 有序的列表（Future.get 顺序回收）。
2. 合并在主线程按 bi 升序进行（pages 去重/critical/entrypoints 并集逻辑不变）→ 输出稳定、与串行等价。
3. phase/心跳：`synchronized(row)` 单点写 "发布中 完成x/y批(并行N)"；model() 流式心跳已有 CAS 节流，多 lane 并发 beat 频率仍低。
4. 失败语义不变：批失败→跳过记缺口；全部失败→"无有效页"抛错。
5. `par<=1` 时走原串行路径（回退开关）。
6. 二期（同任务内可选）：抽取阶段按源并行（每源独立 lane 已具备），并发同信号量；缓存命中源跳过不占并发。

## 验收
- [ ] 8 批并行(3)编译总时长 ≈ 串行的 1/2.5~1/3（实测记录）
- [ ] 并发下合并结果与串行一致（页面顺序按批序、无重复 id、critical/entrypoints 正确）
- [ ] phase 心跳在并行期间持续更新（≤15s），不再"疑似卡住"误报
- [ ] 某批失败仅该批跳过；网关限流(429/❌)走既有重试
- [ ] `-Dwiki.publishParallelism=1` 回退串行行为不变
- [ ] wiki 相关单测全绿（SupplierWikiTest mock 串行调用仍兼容——测试不触发并发路径或 par=1）

## 风险
- 网关对同模型并发限流 → 降并发默认值即可
- 内存：3 个额外 CLI 进程 ≈ +1GB 级 → 机器 8G+ 可接受；不行降 2
- 运行中的旧后端不受影响（本任务只改代码，重启由用户择时）

## 交付说明
本次实现只改代码 + javac 临时目录验证（**不跑 mvn、不重启**，用户有任务在跑）；生效需用户择时重启后端。

## Delivery (2026-11-04)
已实现并上线：Semaphore(3) 虚拟线程并行发布、按批序合并、phase() 加锁心跳、-Dwiki.publishParallelism=1 回退。全量 86 测仅既有环境性 3F+1E；后端 20:26 重启生效（wiki rev16 READY 保留）。提交 e785496。
