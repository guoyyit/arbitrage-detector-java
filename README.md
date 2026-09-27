# 跨市场套利机会检测服务 (arbitrage-detector-java)

一个基于 Spring Boot 3 / Java 17 的**本地**服务：持续接收多个模拟市场的实时报价，
识别扣除每跳手续费与滑点后仍有利可图的循环套利路径（两跳或更多跳），输出可执行建议，
并在高频更新、报价乱序、重复、迟到以及进程中断恢复等条件下保证结果**正确、确定、可复现**。

报价输入通过**内存管道（本地回环）**模拟，不依赖任何外部行情服务；HTTP 接口仅绑定
`127.0.0.1`。

---

## 1. 架构与数据流

```
 多个模拟行情线程
       │  Quote(market, base, quote, bid/ask, bidQty/askQty, quoteTime)
       ▼
 InMemoryQuoteSource（内存管道，线程池异步投递，可并发/乱序/重复）
       ▼
 QuoteIngester   分配单调递增 ingestSeq（到达顺序），调用存储层
       ▼
 QuoteStore      乱序/重复/迟到/过期判定；读写锁；版本号；一致快照
       ▼
 QuoteSnapshot   不可变快照（含 version、创建时间、TTL 后仍有效的报价）
       ▼
 ArbitrageDetector  在单一快照上枚举长度 ≤ maxCycleLength 的货币环，扣费扣滑点，
       │             按阈值/最小成交量过滤；结果确定性排序
       ▼
 OpportunityTracker  迟滞（进入/退出双阈值）生命周期管理，抑制抖动，生成审计事件
       ▼
 StateJournal   JSON-lines 追加日志（QUOTE WAL / EVENT / CHECKPOINT，fsync）
       ▼
 DetectionEngine / DetectionScheduler  周期编排；启动时 recover()；REST 可手动触发
```

包划分：

| 包 | 职责 |
|---|---|
| `domain` | `Quote`、`Hop`、`Opportunity`、`OpportunityEvent` 不可变值对象 |
| `quote` | `QuoteStore`（线程安全存储与快照）、`QuoteSnapshot`（不可变小快照） |
| `input` | `QuoteIngester`（序列号+落盘）、`InMemoryQuoteSource`（内存管道）、`SimulatedFeedRunner`（可选演示行情） |
| `detect` | `ArbitrageDetector`（环枚举、成本、过滤、确定性排序） |
| `opportunity` | `OpportunityTracker`（迟滞、生命周期、审计、恢复对账） |
| `persistence` | `StateJournal`（追加日志、崩溃恢复解析） |
| `engine` | `DetectionEngine`（单周期编排与恢复）、`DetectionScheduler`（单线程周期调度） |
| `config` | `DetectorProperties`（绑定与同步快照）、`ConfigService`（校验+原子更新） |
| `api` | `ArbitrageController`（本地 HTTP：报价/检测/机会/事件/配置） |

---

## 2. 检测语义（Detection Semantics）

### 2.1 报价状态的正确性规则

报价以 `(market, base, quote)` 为键，存储该键最新的一条**已接受**报价。

| 到达情况 | 判定 | 行为 |
|---|---|---|
| `quoteTime < now - quoteTtl` | `EXPIRED` | 拒绝，不改变状态 |
| 已存在更新的 `quoteTime`（乱序/迟到的旧报价） | `STALE` | 拒绝，不改变状态 |
| 与当前报价完全相同（同 `quoteTime`、同买卖价与量） | `DUPLICATE` | 拒绝，不推进版本 |
| 其它 | `ACCEPTED` | 替换该键报价，存储版本号 +1 |

- “迟到但更新”的报价（墙钟到达晚、但 `quoteTime` 最新）仍会被接受。
- 过期不仅在到达时判定，**每次快照都会重新套用 TTL**：报价在 `now - quoteTtl`
  之前则从该快照排除（历史版本号不变，状态可追溯）。
- 存储用 `ReentrantReadWriteLock`：快照在同一把读锁内完成整表复制，任何并发写入
  要么整批可见、要么不可见，**同一次检测绝不会混用新旧报价**。
- 每接受一条报价，存储 `version` 恰好 +1；快照携带其 version，使结果可精确复现。

### 2.2 图、边与成本

每条报价产生两条有向边：

- **SELL** `base -> quote`，汇率 = bid（卖 1 个 base 得 bid 个 quote），
  可成交量（目的币种）= `bidQty * bid`；
- **BUY** `quote -> base`，汇率 = `1 / ask`，可成交量 = `askQty`（base 数量）。

每跳对吃单方计收手续费与滑点：

```
执行汇率 = 原始汇率 × (1 - feeRate) × (1 - slippageRate)
```

一个环（起点货币经过若干跳回到自身）的**净收益率**：

```
netRate = Π(各跳原始汇率) × costFactor^跳数 − 1,   costFactor = (1-feeRate)(1-slippageRate)
```

有效机会需同时满足：

1. `netRate > minProfitRate`（严格大于，扣费后仍有利可图）；
2. 可执行规模（沿各跳深度传播并折算回**起点币种**后的可成交量）`≥ minVolume`；
3. 跳数满足 `2 ≤ 跳数 ≤ maxCycleLength`。

### 2.3 确定性与可复现

- 图按报价键排序构建，邻接边按 `(to, market, side)` 排序；
- DFS 固定从**环中字典序最小的货币**出发，环只输出一次（消除旋转重复）；
- 结果按 `(netRate 降序, 结构 id 升序)` 排序。

因此：**同一快照 + 同一配置 ⇒ 逐元素相同的结果列表**。

机会 `id` 由环的结构（每跳 `market:from>to:SIDE`）派生；同一环在价格变化后 id
不变，从而生命周期可以跨快照、跨重启稳定跟踪。

### 2.4 抖动抑制（迟滞）与可解释生命周期

机会集合采用**进入/退出双阈值**：

- 出现（`APPEARED`）：`netRate > minProfitRate`；
- 保持（`UPDATED`）：已活跃，且在退出阈值之上；仅当
  `|ΔnetRate| > updateEventEpsilon` 才写审计事件，避免微小高频波动刷日志；
- 消失（`DISAPPEARED`）：环不再存在（报价过期/盘口变化），或
  `netRate ≤ minProfitRate − exitMargin`。

在迟滞带 `(exitThreshold, entryThreshold]` 内的高频微小波动**既不产生也不撤销机会**。
每个事件都带 `reason`（进入/越过退出阈值/报价过期或盘口变化）与时间戳，形成完整、
可解释、按序保留的审计链。

### 2.5 中断与恢复

磁盘上的追加日志（JSON-lines，每行一条，每次写入 `flush + fsync`）包含三类记录：

- `QUOTE`：已接受报价的**预写日志**（任何检测使用它之前已持久化）；
- `EVENT`：`APPEARED / UPDATED / DISAPPEARED` 生命周期事件（审计）；
- `CHECKPOINT`：最后一次**完整完成**的检测（ingestSeq、snapshotVersion、活跃数）。

启动时 `DetectionEngine.recover()`：

1. 重放全部 `QUOTE`（经 `store.restore`，恢复后快照仍重新套用 TTL）；
2. 将 ingest 序列恢复到日志最大值，保证**序列号不倒退**；
3. 由事件流重建崩溃时的活跃 id 集合（APPEARED/UPDATED 加入，DISAPPEARED 移除）；
4. 恢复后首次检测：崩溃前已活跃且仍成立的机会**静默刷新、不重复报告**；
   中断期间新出现的机会补报一次 `APPEARED`（**不遗漏**）；已不成立的补 `DISAPPEARED`；
5. 事件先落盘，再写完成检查点。崩溃最多丢失“正在进行中”的那一次检测，重放即可恢复，
   **不会停在中间状态**。

### 2.6 配置变更的原子性

`DetectorProperties` 的读写与 `copy()` 全部在同一监视器上同步；每次检测开始时取
**一份不可变副本**，整次检测只使用该副本。`ConfigService.update()` 先整体校验，
非法值整体拒绝、原配置保持不变；合法才在锁内一次性应用。因此配置切换不可能让某次
检测读到“半新半旧”的参数，也不会产生不可复现的结果。调度器在每个周期边界重新读取
检测频率，下一次调度即按新间隔执行，绝不在检测中途打断。

---

## 3. 配置项（`application.properties`，前缀 `arbitrage`）

| 键 | 默认值 | 含义 |
|---|---|---|
| `fee-rate` | `0.001` | 每跳吃单手续费率（0.1%），范围 `[0,1)` |
| `slippage-rate` | `0.0005` | 每跳不利滑点率（0.05%），范围 `[0,1)` |
| `min-profit-rate` | `0.001` | 扣费后净收益率进入阈值（严格大于） |
| `exit-margin` | `0.0005` | 迟滞宽度；退出阈值 = `min-profit-rate − exit-margin`（≤ min-profit-rate） |
| `update-event-epsilon` | `1e-9` | 记录 `UPDATED` 所需的最小净收益率变化绝对值 |
| `max-cycle-length` | `3` | 环最大跳数，范围 `[2,8]` |
| `quote-ttl` | `5s` | 报价有效期；更早的报价从快照排除（须为正） |
| `detection-interval` | `200ms` | 检测频率（须为正） |
| `min-volume` | `0` | 折算到起点币种的最小可成交量 |
| `journal-path` | `data/journal.log` | 崩溃恢复/审计的追加日志路径 |
| `simulated-feed.enabled` | `false` | 是否开启内置合成行情（仅本地演示） |

---

## 4. 本地 HTTP 接口（仅监听 127.0.0.1:8080）

| 方法 & 路径 | 说明 |
|---|---|
| `POST /api/quotes` | 投递一条模拟报价，返回 `{result: ACCEPTED/STALE/DUPLICATE/EXPIRED, ingestSeq}` |
| `POST /api/detect` | 立即执行一次检测周期，返回本轮事件与活跃数 |
| `GET  /api/opportunities` | 当前活跃机会（含每跳市场/方向/价格、净收益率、可成交量） |
| `GET  /api/events` | 生命周期审计事件（APPEARED/UPDATED/DISAPPEARED + 原因） |
| `GET  /api/config` | 当前配置视图 |
| `PUT  /api/config` | 原子更新配置；非法返回 `400 {"errors":[...]}`，旧配置不变 |

快速运行（可选开启演示行情）：

```bash
mvn spring-boot:run
# 或：mvn spring-boot:run -Dspring-boot.run.arguments=--arbitrage.simulated-feed.enabled=true
```

也可不经 HTTP，直接在代码中使用 `QuoteIngester` + `InMemoryQuoteSource`
（`pushSync` 保序投递，`push` 经线程池异步/并发投递，`drain` 等待排空）。

---

## 5. 自动化测试

共 37 个测试（`mvn test`），覆盖任务要求的全部关键场景：

| 测试类 | 覆盖场景 |
|---|---|
| `QuoteStoreTest` | 新鲜接受、**重复**、**乱序(STALE)**、**迟到但更新**、到达即**过期**、快照期过期、多市场/交易对隔离、版本号语义 |
| `ArbitrageDetectorTest` | 两跳跨市场盈利环、扣费后无利可图、三跳三角环、`maxCycleLength` 上限、最小成交量过滤、同快照确定性、旋转去重、结构 id 跨快照稳定 |
| `SnapshotConsistencyTest` | 8 路并发 200 更新下快照始终自洽（无重复键/无过期泄漏/版本单调）；并发更新中同一快照重复检测结果逐元素相同 |
| `OpportunityTrackerTest` | 进入阈值、迟滞带内抖动不增删、退出阈值语义、环消失、审计链按序且可解释 |
| `RecoveryTest` | 日志往返；崩溃恢复**不重复**报告已报机会、**不遗漏**中断期新机会、序列号不倒退；二次重启幂等；崩溃前已撤销的机会不会复活 |
| `InMemoryQuotePipeTest` | 内存管道多 feed 并发投递、乱序/重复/迟到端到端、TTL 快照排除、异步投递序列号单调 |
| `EngineFlappingTest` | 完整 `runOnce` 周期上 40 次高频微波动零事件抖动、真实越过退出带恰好一次 DISAPPEARED；可控时钟下报价过期撤销且原因可解释 |
| `ConfigServiceTest` | 合法配置原子生效、非法配置整体拒绝且旧值不变、迟滞参数一致性、快照副本独立 |
| `ApiIntegrationTest` | 经 HTTP 投递→检测→查询机会/事件/配置；非法配置 400 且旧配置保留 |
| `ApplicationTests` | Spring 上下文装配冒烟 |

> 时间相关测试使用注入的 `Clock` 与临时目录（JUnit `@TempDir`），无需等待真实墙钟，
> 也不会在仓库内留下运行态日志。

## 6. 设计取舍与边界

- 成本模型把每跳手续费与滑点统一视为对吃单方不利的比例折损（乘法、作用于汇率）。
- 成交量沿每跳盘口深度传播并折算回起点币种；链上最浅一跳决定整环可成交量。
- 环枚举为简单环（货币不重复，除闭合回到起点），上限 `maxCycleLength ≤ 8`，
  避免组合爆炸；对货币数量有限的币种场景足够。
- 机会只表达“净收益率为正的可执行循环建议”，不做自动下单；执行层不在本服务范围。
