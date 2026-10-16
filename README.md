# 轻量级可编排规则引擎（架构骨架）

参考 ThingsBoard 规则引擎与 eKuiper 图规则 API 的设计，去掉 Actor 模型与 protobuf，
保留真正有价值的部分。**核心零外部依赖，只依赖 JDK 8+。**

---

## 一、分层结构

| 层 | 包 | 职责 | 关键类 |
|---|---|---|---|
| 模型层 | `model` | 不可变消息、元数据、处理栈 | `RuleMessage` `MetaData` `ProcessingStack` |
| 契约层 | `spi` | 节点 SPI、上下文、注册表、表达式 | `RuleNode` `NodeContext` `NodeRegistry` `ExpressionEngine` `MessageSink` |
| 编排层 | `chain` | DAG 定义与执行 | `RuleChain` `ChainExecutor` `DefaultNodeContext` |
| 运行层 | `runtime` | 分区串行、批次、提交与重试策略 | `PackProcessor` `PackContext` `KeyOrderedExecutor` `SubmitStrategy` `RetryStrategy` |
| 治理层 | `config` | JSON 规则链定义与加载 | `JsonChainLoader` `MiniJson` |
| 观测层 | `observability` | 统计与追踪 | `EngineObserver` |
| 节点库 | `nodes` | 内置示例节点 | `LogNode` `FilterNode` `TransformNode` `AiDecisionNode` `AsyncSinkNode` |
| 门面 | `RuleEngine` | 组装所有层 | `RuleEngine` |

依赖关系严格单向：`runtime → chain → spi → model`，`config` 与 `observability` 只被上层引用。

---

## 二、从 ThingsBoard 抄来的关键设计（附源码出处）

| 设计 | 本框架落点 | ThingsBoard 出处 |
|---|---|---|
| 消息不可变 + Builder/Transformer | `RuleMessage.Transformer` | `TbMsg.TbMsgTransformer` |
| transform 时总是拷贝处理栈 | `Transformer.build()` | 同左，保证 fan-out 互不污染 |
| 关系名代替 if/else | `NodeContext.tellNext(msg, relation)` | `TbContext.tellNext` |
| pendingMap 即超时集合 | `PackContext.pendingMapSnapshot()` | `TbRuleEngineProcessingResult` |
| CountDownLatch + AtomicInteger 提前放行 | `PackContext.await()` | `TbMsgPackProcessingContext` |
| 三个 boolean 组合出多种重试策略 | `RetryStrategy` 工厂方法 | `TbRuleEngineProcessingStrategyFactory` |
| 提交策略与重试策略正交 | `SubmitStrategy` / `RetryStrategy` | 同左 |
| 处理栈防循环 + 支持嵌套链 | `DefaultNodeContext.input/output` | 同左 |
| 配置版本迁移口子 | `RuleNode.upgrade()` | `TbNode.upgrade()` |
| 节点无状态（反例警示） | 见 `AiDecisionNode` 注释 | `TbMsgDelayNode` 因内存状态被废弃 |
| I/O 外置线程池 | `NodeContext.asyncExecutor()` | `TbContext.getExternalCallExecutor()` |
| 批次级超时独立于提交策略 | `packTimeoutMs` | `pack-processing-timeout` |

### 刻意不抄的部分

- **Actor 模型**：改用 `KeyOrderedExecutor`（按 originator 哈希 + 单线程通道），不到 100 行，堆栈完整可调试
- **TBEL 自研脚本语言**：改为 `ExpressionEngine` 接口，直接接你的 Jsonata
- **protobuf 序列化**：改为可插拔的 `MessageSource` / `MessageSink`，接 Kafka/RabbitMQ
- **`TbContext` 上帝接口**：改为 `ctx.capability(Xxx.class)` 按需注入，避免节点与平台死耦合

---

## 三、规则链 JSON 格式

与 eKuiper 图规则 API 同构，可直接映射成拖拽画布：

```json
{
  "id": "fireAlarm",
  "name": "消防告警处理链",
  "nodes": {
    "recv":       { "type": "log",       "props": { "prefix": "[接收]" } },
    "riskFilter": { "type": "filter",    "props": { "expr": "temp > 80" } },
    "aiJudge":    { "type": "aiDecision","props": { "agent": "riskJudge" } }
  },
  "edges": {
    "recv":       [ { "to": "riskFilter" } ],
    "riskFilter": [ { "to": "aiJudge", "relation": "True" },
                    { "to": "discard", "relation": "False" } ],
    "aiJudge":    [ { "to": "persist", "relation": "有隐患" },
                    { "to": "discard", "relation": "无隐患" } ]
  },
  "entry": [ "recv" ]
}
```

`edge` 未声明 `relation` 时默认 `Success`——串联场景最简，分支场景再显式指定。

---

## 四、接入你已有的资产

### 1. Jsonata 字段映射引擎

实现三个方法即可，引擎其他部分零改动：

```java
public class JsonataEngine implements ExpressionEngine {
    public boolean evaluate(String expr, RuleMessage msg) { /* Jsonata 布尔判定 */ }
    public String  transform(String expr, RuleMessage msg) { /* Jsonata 字段映射 */ }
    public Set<String> route(String expr, RuleMessage msg) { /* 返回关系名 */ }
}
```

`TransformNode` 随即具备"新增设备类型只改配置不改代码"的能力。

### 2. 四个消防 AI Agent

`AiDecisionNode` 把智能体结论**直接当作关系名**输出，节点里一行 if/else 都不用写：

```java
.capability(AiDecisionNode.DecisionService.class, (agent, msg) -> {
    // 智策巡卫 / 智巡助手 / 风险智判 / 慧眼智析
    return riskJudgeAgent.decide(msg);   // "有隐患" / "无隐患" / "需现场复核"
})
```

三条硬约束已写在类的注释里：**必须走 asyncExecutor、必须检查 msg.isValid()、必须规则预筛 + AI 抽稀**。

### 3. Kafka / RabbitMQ

实现 `MessageSource`（拉取）与 `MessageSink`（投递）两个接口：

```java
public class KafkaMessageSource implements MessageSource {
    public List<Envelope> poll(int maxCount) { /* consumer.poll() */ }
    public void commit() { /* consumer.commitSync() */ }
    public String name() { return "main"; }
}
```

单机/测试用内置的 `InMemoryMessageSource`。

---

## 五、并发模型：为什么不用 Actor

`KeyOrderedExecutor` 用"固定 N 条单线程通道 + 按 key 哈希路由"实现：

- 同一设备（originator）的消息**严格串行**——状态更新、告警判定依赖顺序
- 不同设备**并行**——百万设备下吞吐靠通道数水平扩展
- 队列**有界** + `CallerRunsPolicy`——队列满时提交方自己执行，形成背压而非 OOM

相比 Akka/Pekko：少了监督树、远程通信、调试断裂，多了可读性。
你要的只是"状态私有 + 串行"，不需要整套 Actor 框架。

## 六、后续可扩展点

- [ ] 规则链热部署（配置存 Nacos/MySQL，监听变更重建 `RuleChain`）
- [ ] 分布式：实现 `MessageSink` 接 Kafka，配合一致性哈希做分区分配
- [ ] 可视化：`JsonChainLoader` 的格式已与画布一一对应，补前端拖拽即可
- [ ] 死信队列：`RetryStrategy` 放弃的消息投递到 DLQ
- [ ] 链路追踪：`correlationId` 贯穿消息，接入 OpenTelemetry

---

## 七、已修复的 Bug（编译验证过程中发现）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 同步提交时整批消息被静默跳过 | `processPack` 用 `while(running)`，而 `running` 只在 `run()` 中置 true | 改为 `do{...}while(running)` |
| 2 | 串行策略下某设备消息全部卡到超时 | 只在 `onSuccess` 时放行同 key 下一条，一条失败就永久堵住 | `SubmitStrategy` 增加 `onFailure`，失败同样放行 |
| 3 | 消息无 chainId 时静默丢弃 | `chains.get(null)` 返回 null 后直接 onError | `ChainExecutor` 支持 `defaultChainId`，单链时自动兜底 |
| 4 | `execute` 无限递归 | 默认链逻辑里递归调用了自身 | 拆出 `executeInternal` |
| 5 | Fan-out 时只有第一个分支生效 | 任一支路完成即 ack 整条消息，其余分支结果被丢弃 | `PackCallback.onFanOut(n)` + `PackContext` 分支计数，全部分支完成才收口 |
| 6 | `UnsupportedOperationException` | `BurstSubmitStrategy` 直接持有并 `clear()` 传入的不可变列表 | `init` 时拷贝为 `ArrayList` |
| 7 | 重试一次后直接放弃 | 同上 bug 1，`submitSync` 场景 running=false 导致重试循环提前退出 | 用 `started` 标志区分"从未启动"与"已停止" |
| 8 | 嵌套链子链结果被吞掉 | 子链走到尽头直接 ack，未回跳主链 | `output` 改为派发到「调用者节点的下游」，支持多层回溯 |

> 这几条都是"单机跑得顺、上线才炸"的类型，值得作为自研引擎的 checklist。

## 八、验证结果

Maven 3.9.16 + JDK 11 编译通过，65 个 class。

```
[1] 串行顺序      PASS  同设备 5 条严格按序
[2] Fan-out       PASS  一条消息发散到 2 个下游
[3] 嵌套链        PASS  子链执行且回到主链下游
[4] 失败重试      PASS  前两次失败，重试后成功（共调用 3 次）
[5] 批次超时      PASS  节点卡住时批次超时而非永久挂起
[6] 节点无状态    PASS  20 条消息各自独立不串扰
结果: pass=6, fail=0
```

压测（20 设备 × 200 条）：**16000 msg/s，0 顺序异常**。

运行方式：

```bash
mvn compile
# 功能验证
java -cp target/classes:<test-classes> io.fire.rule.Verify
# 压测
java -cp target/classes:<test-classes> io.fire.rule.Stress
# 端到端演示
java -cp target/classes:<test-classes> io.fire.rule.EngineDemo
```

## 九、已知限制

- **fan-out 的失败语义**：任一分支失败则整条消息判失败（会等其余分支跑完再收口）。如需"部分成功"，可扩展 `PackContext` 分别记账
- **`SimpleExpressionEngine` 是占位实现**，只支持 `temp > 80` 这类简单比较，生产请换 Jsonata / Aviator
- **`tellSelf` 不落队列**：重启即丢，与 ThingsBoard `TbMsgDelayNode` 同样的取舍，仅适合周期任务与超时检查
- **多链场景建议显式设置 `defaultChainId`**，否则消息必须自带 chainId

---

# 分布式扩展

## 架构：两层分区

```
消息 → 按 originator 哈希 → Kafka 分区（跨实例） → 本实例 lane（跨线程） → 串行执行
```

同一 originator 恒定映射到同一分区 → 被固定实例消费 → 进固定 lane，**顺序在全链路成立**。

## 新增组件

| 类 | 职责 |
|---|---|
| `PartitionRouter` / `HashPartitionRouter` | 分区路由，决定消息归哪个实例 |
| `TransportMessage` | 跨节点传输载体（含 relations） |
| `MessageSerializer` / `JsonMessageSerializer` | 序列化（可换 Protobuf/Kryo） |
| `DistributedMessageSink` | **本地优先**投递：本地内存路由，远程才序列化 |
| `InMemoryClusterBroker` | 内存 broker，用于多实例模拟与测试 |
| `PartitionedMessageSource` | 消费本实例分区并还原消息 |
| `KafkaMessageSource` | Kafka 实现示例（需引入 kafka-clients） |

## 关键设计

**1. 本地优先（性能核心）**
目标分区在本实例时直接内存 dispatch，零序列化零网络。
因为「设备 → 分区 → 实例」映射稳定，链路后续各跳大概率仍在同一实例内，
**大多数消息不产生网络开销**。

**2. relations 随消息传输**
跨节点时必须把"这条消息要走哪些关系"一起传过去——
关系是路由指令，不属于消息本身，因此放在传输层（与 ThingsBoard proto 的 `relationTypes` 同构）。

**3. resume 而非 re-execute**
对端收到消息后**不重新执行 nodeId**，只模拟"该节点调用了 tellNext"的效果，
直接按关系继续路由。这是 `ChainExecutor.resume()` 存在的唯一原因。

## 启用方式

```java
HashPartitionRouter router = HashPartitionRouter.staticAssign("node-1", 12, 0, 3);
RuleEngine engine = RuleEngine.builder()
        .registry(registry)
        .IExpressionEngine(engine)
        .source(new PartitionedMessageSource("main", router, serializer, broker, 256))
        .partitionRouter(router)
        .messageSerializer(new JsonMessageSerializer())
        .remoteTransport(kafkaTransport)   // 实现 RemoteTransport 接 Kafka
        .build();
```

生产环境把 `RemoteTransport` 换成 Kafka 生产者即可，引擎代码零改动。

## 分布式验证结果

```
[1] 分区路由一致性    PASS  三实例对同设备算出同分区 / 12 分区完整覆盖 / 每设备只归属一实例
[2] 跨实例恢复        PASS  按原关系继续路由，cross 节点未被重复执行
[3] 本地优先优化      PASS  单实例独占分区时零跨网络投递
[4] 分布式顺序保证    PASS  同设备 100 条恒定落同分区 / 1000 设备均衡分散
结果: pass=9, fail=0
```

## 分布式下的注意事项

- **分区数决定并行度上限**，扩容需配合 rebalance（Kafka consumer group 自动完成）
- **消费者数 ≤ 分区数**，多了会空转
- **分区键必须是 originator**，换别的键会直接破坏顺序保证
- **手动提交 offset**，自动提交会在处理失败时丢消息
#   r u l e - e n g i n e  
 