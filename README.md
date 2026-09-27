# Arbitrage Detector

跨市场套利机会检测服务（Java 17 / Spring Boot 3）。服务持续接收多个市场的模拟实时报价，
在一致的报价快照上检测扣除手续费与滑点后仍有利可图的循环套利路径，输出可执行建议，
并保证在高频更新、报价乱序与进程中断恢复下结果正确、可复现且稳定。

报价输入完全在进程内模拟（内存管道 + 本地回环），不依赖任何外部服务。

## 构建与运行

```bash
mvn compile          # 编译
mvn test             # 运行全部测试
mvn spring-boot:run  # 启动服务（默认端口 8080）
```

启动后模拟行情源自动开始推送报价，检测循环按 `arbitrage.detect-interval-millis` 周期运行。

## 检测语义

### 报价状态（QuoteBook）

- 每个 `(market, base/quote)` 槽位只保留最新报价。
- 每个报价源对每个槽位使用**严格递增的序号（sequence）**：
  - 序号小于已存报价 → 判为**乱序（STALE_SEQUENCE）**，丢弃；
  - 序号相等 → 判为**重复（DUPLICATE）**，丢弃；
  - 到达时 `timestamp + quoteTtlMillis <= now` → 判为**迟到/过期（EXPIRED）**，丢弃。
- 快照（snapshot）在读写锁保护下生成，是**真实时间点一致**的不可变视图；
  过期报价不进入快照。检测只针对快照执行，绝不混用新旧报价。

### 套利环检测（ArbitrageDetector）

- 每条报价贡献两条有向边：
  - `base -> quote`，汇率 = `bid`，容量 = `bidVolume`（base 计）；
  - `quote -> base`，汇率 = `1/ask`，容量 = `askVolume * ask`（quote 计）。
- 枚举长度 `2..maxCycleLength` 的简单环。每跳净汇率 =
  `rate * (1 - feeRate) * (1 - slippageRate)`。
- 环有效当且仅当：
  - **净收益** `Π(净汇率) - 1 > minProfit`（严格大于）；
  - **瓶颈起始量**（各跳容量折算到起始币种的最小值）`>= minVolume`。
- 确定性：节点与边按字典序处理，每个环以其最小币种为起点恰好枚举一次；
  结果按净收益降序、签名升序排列。**同一快照 + 同一配置 ⇒ 完全相同的结果。**
- 机会签名（signature）形如 `m2:BTC->USD|m1:USD->BTC`，跨周期稳定，用于去重与审计。

### 机会生命周期（OpportunityTracker）

防抖（flicker suppression）双机制：

- **滞回（hysteresis）**：新机会净收益须超过 `minProfit` 才 APPEARED；
  已激活机会跌破 `minProfit * (1 - exitHysteresis)`（退出阈值）之前不会消失。
- **宽限 TTL**：已激活机会暂时未被检出时，存活至距最后一次检出超过
  `opportunityTtlMillis` 才 DISAPPEARED。

事件类型（均带人可读 reason，写入审计日志与日志文件）：

| 事件 | 含义 |
|---|---|
| `APPEARED` | 净收益上穿进入阈值，首次报告 |
| `REFRESHED` | 激活中，跌破进入阈值后重新上穿（未触发重复 APPEARED） |
| `DISAPPEARED` | 超过宽限 TTL 未被检出（低于退出阈值或成交量塌陷） |

### 中断恢复（JournalStore）

- 每个检测周期的事件作为**一整行**原子追加到
  `<journalDir>/opportunity-journal.log`。
- 崩溃最多少写最后一行（撕裂行），恢复时解析失败即跳过；
  该周期会在重启后基于最新报价重新检测——**不漏报**。
- 恢复时按序重放全部完整周期，重建激活机会集合——**不重复报告**已报告机会。
- 重启后中断期间新出现的机会，在首个周期正常检出并报告。

### 配置变更

- 配置是不可变对象，引擎持有 `AtomicReference`；`PUT /api/config` 整体原子替换，
  **下一个周期**生效。进行中的周期永远使用同一份配置，不会停在中间状态。
- 检测间隔在周期间重新读取，调小立即生效。

## 配置项（application.properties / PUT /api/config）

| 键 | 默认 | 含义 |
|---|---|---|
| `arbitrage.fee-rate` | 0.001 | 每跳手续费率，[0,1) |
| `arbitrage.slippage-rate` | 0.0005 | 每跳滑点率，[0,1) |
| `arbitrage.min-profit` | 0.001 | 净收益进入阈值（严格大于），>= 0 |
| `arbitrage.max-cycle-length` | 4 | 最大环长（跳数），[2,8] |
| `arbitrage.quote-ttl-millis` | 5000 | 报价有效期（毫秒） |
| `arbitrage.detect-interval-millis` | 1000 | 检测周期（毫秒） |
| `arbitrage.min-volume` | 0.0001 | 最小可成交起始量（起始币种计） |
| `arbitrage.exit-hysteresis` | 0.5 | 滞回比例，退出阈值 = minProfit * (1 - h)，[0,1) |
| `arbitrage.opportunity-ttl-millis` | 30000 | 机会宽限 TTL（毫秒） |
| `arbitrage.journal-dir` | data | 恢复日志目录（修改需重启生效） |

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/opportunities` | 当前激活的套利机会（含路径、净收益、可成交量） |
| GET | `/api/events` | 最近的生命周期事件（审计记录，容量 1000） |
| GET | `/api/quotes` | 当前有效报价（一致快照） |
| POST | `/api/quotes` | 通过内存管道注入一条报价（回环输入） |
| GET | `/api/config` | 当前配置 |
| PUT | `/api/config` | 原子更新配置（null 字段保持原值），下一周期生效 |

注入报价示例：

```bash
curl -X POST localhost:8080/api/quotes -H 'Content-Type: application/json' -d '{
  "market":"m9","base":"BTC","quote":"USD",
  "bid":50000,"ask":50010,"bidVolume":5,"askVolume":5,
  "sequence":1,"timestamp":'"$(date +%s000)"'}'
```

## 架构

```
SimulatedQuoteSource ──send──> InMemoryQuotePipe ──onQuote──> QuoteBook
                                                                   │ snapshot (RW-lock)
                                              DetectionEngine: purge → snapshot → detect
                                                                   │            (ArbitrageDetector,
                                                                   │             纯函数、确定性)
                                              OpportunityTracker <── candidates
                                                   │ events
                                              JournalStore (append-only, 按周期原子行)
                                                   │ replay on restart
                                              REST API (opportunities / events / config)
```

## 测试

`mvn test` 覆盖（26 个用例）：

- **乱序 / 重复 / 迟到 / 过期**：`QuoteBookTest`
- **环检测正确性、环长上限、最小成交量、确定性**：`ArbitrageDetectorTest`
- **并发快照一致性与可复现**：`SnapshotConsistencyTest`
- **机会抖动抑制（滞回 + 宽限 TTL）**：`OpportunityTrackerTest`
- **中断恢复（不漏报、不重复报、撕裂行容忍）**：`RecoveryTest`
- **配置变更原子性与历史重放可复现**：`EngineConfigChangeTest`
- **内存管道 FIFO**：`InMemoryQuotePipeTest`
