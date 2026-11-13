package io.fire.rule.spring.flowgram;

import io.fire.rule.core.chain.ChainExecutor;
import io.fire.rule.core.chain.ExecutionCallback;
import io.fire.rule.core.chain.ExecutionContext;
import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.flowgram.node.ConditionNode;
import io.fire.rule.spring.flowgram.node.EndNode;
import io.fire.rule.spring.flowgram.node.LoopNode;
import io.fire.rule.spring.flowgram.node.StartNode;
import io.fire.rule.spring.flowgram.workflow.value.ValueResolver;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一 State 模型端到端黄金行为锁：以内建控制流节点（start/end/condition/loop）
 * + 测试业务节点，用<b>同线程执行器</b>确定性地跑通线性链、fan-out 分支隔离、Condition 分支、
 * Loop 子链逐轮迭代，断言最终 {@link ExecutionContext#lastMessage()} 的 {@link ExecutionState}
 * 命名空间产出 / 入口负载 / 路由行为与"改造前后等价"的期望一致。
 *
 * <p>随 {@code WorkflowSchemaLoader} 与循环语义下沉到 cmp 而迁移至此：装载器解析 start/end/condition/loop
 * 全走 {@link INodeRegistry}，故测试注册表需挂上真实控制节点（含 {@link LoopNode}），循环用例走完整节点链路。
 *
 * @author Cai FeiYue
 */
class ChainExecutorStateFlowTest {

    /** 同线程执行器：投递即递归跑完，{@code execute} 返回时全链已终止，断言无异步竞态。 */
    private static final Executor SAME_THREAD = Runnable::run;

    private static final IExpressionEngine NO_OP_ENGINE = new IExpressionEngine() {
        @Override
        public boolean evaluate(String expr, ExecutionState state) {
            return false;
        }

        @Override
        public String transform(String expr, ExecutionState state) {
            return state.toJson();
        }

        @Override
        public Set<String> route(String expr, ExecutionState state) {
            return Collections.emptySet();
        }
    };

    // ================= 线性链：start → assign(ref/const) → end =================

    @Test
    void linearChainPropagatesEntryAndNamespacedOutputs() {
        String chain = "{"
                + "\"id\":\"c-linear\","
                + "\"nodes\":["
                + "  {\"id\":\"s\",\"type\":\"start\",\"data\":{}},"
                + "  {\"id\":\"a\",\"type\":\"assign\",\"data\":{\"inputsValues\":{"
                + "      \"greeting\":{\"type\":\"constant\",\"content\":\"hi\"},"
                + "      \"from\":{\"type\":\"ref\",\"content\":[\"s\",\"name\"]}}}},"
                + "  {\"id\":\"e\",\"type\":\"end\",\"data\":{\"inputsValues\":{"
                + "      \"answer\":{\"type\":\"ref\",\"content\":[\"a\",\"greeting\"]}}}}"
                + "],"
                + "\"edges\":["
                + "  {\"sourceNodeID\":\"s\",\"targetNodeID\":\"a\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"a\",\"targetNodeID\":\"e\",\"sourcePortID\":\"default\"}"
                + "]}";

        ExecutionState out = run(chain, "{\"name\":\"world\"}");

        // start 把入口负载整体作为产出，供下游 ref
        assertEquals("world", out.read("s", "name"));
        // assign 解析 constant + ref 后 emit 到命名空间
        assertEquals("hi", out.read("a", "greeting"));
        assertEquals("world", out.read("a", "from"));
        // end 汇总输入写入 outputs[e] 并收口
        assertEquals("hi", out.read("e", "answer"));
        // 入口负载全程不被改写，仍原样可见
        assertEquals("world", out.entry().get("name"));
    }

    // ================= fan-out：两分支各写命名空间、互不串写、各自收口 =================

    @Test
    void fanOutBranchesAreIsolatedAndBothComplete() {
        String chain = "{"
                + "\"id\":\"c-fanout\","
                + "\"nodes\":["
                + "  {\"id\":\"s\",\"type\":\"start\",\"data\":{}},"
                + "  {\"id\":\"f\",\"type\":\"fork\",\"data\":{\"ports\":[\"p1\",\"p2\"]}},"
                + "  {\"id\":\"n1\",\"type\":\"tag\",\"data\":{\"tag\":\"b1\"}},"
                + "  {\"id\":\"n2\",\"type\":\"tag\",\"data\":{\"tag\":\"b2\"}},"
                + "  {\"id\":\"e1\",\"type\":\"end\",\"data\":{}},"
                + "  {\"id\":\"e2\",\"type\":\"end\",\"data\":{}}"
                + "],"
                + "\"edges\":["
                + "  {\"sourceNodeID\":\"s\",\"targetNodeID\":\"f\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"f\",\"targetNodeID\":\"n1\",\"sourcePortID\":\"p1\"},"
                + "  {\"sourceNodeID\":\"f\",\"targetNodeID\":\"n2\",\"sourcePortID\":\"p2\"},"
                + "  {\"sourceNodeID\":\"n1\",\"targetNodeID\":\"e1\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"n2\",\"targetNodeID\":\"e2\",\"sourcePortID\":\"default\"}"
                + "]}";

        // tag 节点记录自己"进入时可见的命名空间键"（证明分支隔离）与"实际被执行"（证明分支收口）
        List<String> n1Sees = new CopyOnWriteArrayList<>();
        List<String> n2Sees = new CopyOnWriteArrayList<>();
        List<String> executed = new CopyOnWriteArrayList<>();
        RuleMessage result = runRaw(chain, "{\"k\":\"v\"}", new ErrorCallback(),
                registryWith(n1Sees, n2Sees, executed));

        // 两个分支各被跑过一次，fan-out 未漏派任何一侧
        assertTrue(executed.contains("n1"), "分支 n1 应被执行");
        assertTrue(executed.contains("n2"), "分支 n2 应被执行");

        // 隔离：分支 n1 进入时看不到 n2 的产出，反之亦然（两分支从同一 fork 快照派生）
        assertFalse(n1Sees.contains("n2"), "分支 n1 不应看到分支 n2 的命名空间产出");
        assertFalse(n2Sees.contains("n1"), "分支 n2 不应看到分支 n1 的命名空间产出");
        assertTrue(n1Sees.contains("s"), "两分支都应能看到上游 start 的产出");

        // 最后收口的快照里，本分支命名空间与入口负载俱在
        ExecutionState out = result.getState();
        assertEquals("v", out.entry().get("k"));
    }

    // ================= Condition：命中 true 分支，else 分支不执行 =================

    @Test
    void conditionRoutesMatchedBranchOnly() {
        String chain = "{"
                + "\"id\":\"c-cond\","
                + "\"nodes\":["
                + "  {\"id\":\"s\",\"type\":\"start\",\"data\":{}},"
                + "  {\"id\":\"c\",\"type\":\"condition\",\"data\":{\"conditions\":["
                + "     {\"key\":\"big\",\"value\":{"
                + "        \"left\":{\"type\":\"ref\",\"content\":[\"s\",\"score\"]},"
                + "        \"operator\":\"gt\","
                + "        \"right\":{\"type\":\"constant\",\"content\":50}}}]}},"
                + "  {\"id\":\"hit\",\"type\":\"tag\",\"data\":{\"tag\":\"big\"}},"
                + "  {\"id\":\"miss\",\"type\":\"tag\",\"data\":{\"tag\":\"else\"}},"
                + "  {\"id\":\"e\",\"type\":\"end\",\"data\":{}}"
                + "],"
                + "\"edges\":["
                + "  {\"sourceNodeID\":\"s\",\"targetNodeID\":\"c\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"c\",\"targetNodeID\":\"hit\",\"sourcePortID\":\"big\"},"
                + "  {\"sourceNodeID\":\"c\",\"targetNodeID\":\"miss\",\"sourcePortID\":\"else\"},"
                + "  {\"sourceNodeID\":\"hit\",\"targetNodeID\":\"e\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"miss\",\"targetNodeID\":\"e\",\"sourcePortID\":\"default\"}"
                + "]}";

        ExecutionState out = run(chain, "{\"score\":88}");

        // 88 > 50 命中 big → 走 hit，未走 miss
        assertEquals("big", out.read("hit", "tag"));
        assertNull(out.outputOf("miss"), "未命中的 else 分支不应执行");
        assertTrue(out.outputs().containsKey("e"), "命中分支应抵达 end 收口");
    }

    // ================= Loop：逐轮迭代子链、产出回收并继续 =================

    @Test
    void loopRunsInlineSubChainPerItem() {
        List<Integer> doubled = new CopyOnWriteArrayList<>();
        String chain = "{"
                + "\"id\":\"c-loop\","
                + "\"nodes\":["
                + "  {\"id\":\"s\",\"type\":\"start\",\"data\":{}},"
                + "  {\"id\":\"L\",\"type\":\"loop\",\"data\":{"
                + "     \"loopFor\":{\"type\":\"ref\",\"content\":[\"s\",\"nums\"]},"
                + "     \"loopOutputs\":{\"last\":{\"type\":\"ref\",\"content\":[\"dub\",\"result\"]}}},"
                + "     \"blocks\":[{\"id\":\"dub\",\"type\":\"each\",\"data\":{}}]},"
                + "  {\"id\":\"e\",\"type\":\"end\",\"data\":{}}"
                + "],"
                + "\"edges\":["
                + "  {\"sourceNodeID\":\"s\",\"targetNodeID\":\"L\",\"sourcePortID\":\"default\"},"
                + "  {\"sourceNodeID\":\"L\",\"targetNodeID\":\"e\",\"sourcePortID\":\"default\"}"
                + "]}";

        ExecutionState out = run(chain, "{\"nums\":[1,2,3]}", registryWithLoop(doubled));

        // 每轮迭代作用域可读，逐轮处理 [1,2,3] → [2,4,6]
        assertEquals(3, doubled.size());
        assertEquals(2, doubled.get(0).intValue());
        assertEquals(4, doubled.get(1).intValue());
        assertEquals(6, doubled.get(2).intValue());
        // loopOutputs 回收末轮产出写入 outputs[L]
        assertEquals(6, ((Number) out.read("L", "last")).intValue());
        assertTrue(out.outputs().containsKey("e"), "Loop 默认端口应继续到 end");
    }

    // ================= 运行工具 =================

    private ExecutionState run(String chainJson, String payload) {
        return run(chainJson, payload, defaultRegistry());
    }

    private ExecutionState run(String chainJson, String payload, INodeRegistry registry) {
        return runRaw(chainJson, payload, new ErrorCallback(), registry).getState();
    }

    /** 装载链、构造信封、同线程跑一遍，返回承载最终态的信封。 */
    private RuleMessage runRaw(String chainJson, String payload,
                               ExecutionCallback callback, INodeRegistry registry) {
        TestChainStore store = new TestChainStore(new WorkflowSchemaLoader(registry));
        RuleChain chain = store.registerChain(JsonUtil.parseObject(chainJson));

        ChainExecutor executor = ChainExecutor.builder()
                .chainStore(store)
                .expressionEngine(NO_OP_ENGINE)
                .asyncExecutor(SAME_THREAD)
                .valueResolver(new ValueResolver())
                .build();

        RuleMessage message = RuleMessage.newMessage()
                .type("TEST")
                .originator("tester")
                .payload(payload)
                .build();

        ExecutionContext execCtx = new ExecutionContext(callback);
        executor.execute(message, chain.getId(), execCtx);
        executor.shutdown();

        assertTrue(callback instanceof ErrorCallback);
        assertNull(((ErrorCallback) callback).error, "执行不应产生错误回调");
        RuleMessage last = execCtx.lastMessage();
        assertTrue(last != null, "至少应有一次流转快照");
        return last;
    }

    // ================= 测试业务节点注册表 =================

    /** 内置控制流节点：cmp 装载器对所有类型都走 registry.get，故每个注册表都要挂上它们。 */
    private Map<String, RuleNode> controlNodes() {
        Map<String, RuleNode> map = new LinkedHashMap<>();
        map.put(WorkflowConstants.TYPE_START, new StartNode());
        map.put(WorkflowConstants.TYPE_END, new EndNode());
        map.put(WorkflowConstants.TYPE_CONDITION, new ConditionNode());
        map.put(WorkflowConstants.TYPE_LOOP, new LoopNode());
        return map;
    }

    /** 默认注册表：assign（emit resolvedInputs）、tag（emit {tag}）。 */
    private INodeRegistry defaultRegistry() {
        Map<String, RuleNode> map = controlNodes();
        map.put("assign", new AssignNode());
        map.put("tag", new TagNode());
        return new MapRegistry(map);
    }

    /** fan-out 用：fork（按 ports 投递）+ 记录两分支进入时可见命名空间与被执行情况的 tag 节点。 */
    private INodeRegistry registryWith(List<String> n1Sees, List<String> n2Sees, List<String> executed) {
        Map<String, RuleNode> map = controlNodes();
        map.put("fork", new ForkNode());
        map.put("tag", new RecordingTagNode(n1Sees, n2Sees, executed));
        return new MapRegistry(map);
    }

    /** loop 用：each（读迭代作用域 item，×2 记录并 emit {result}）。 */
    private INodeRegistry registryWithLoop(List<Integer> doubled) {
        Map<String, RuleNode> map = controlNodes();
        map.put("each", new EachNode(doubled));
        return new MapRegistry(map);
    }

    // ================= 节点实现 =================

    /** 汇聚 inputsValues 后 emit（等价 example 的 AssignNode 语义）。 */
    private static final class AssignNode implements RuleNode {
        @Override
        public void onMsg(INodeContext ctx) {
            ctx.emit(new LinkedHashMap<>(ctx.resolvedInputs()));
            ctx.next();
        }
    }

    /** 直接 emit 配置的 tag 值后走默认端口。 */
    private static class TagNode implements RuleNode {
        @Override
        public void onMsg(INodeContext ctx) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tag", ctx.nodeConfig().get("tag"));
            ctx.emit(out);
            ctx.next();
        }
    }

    /** tag 节点变体：进入时记录"当前可见的命名空间键"（断言分支隔离）与自身被执行（断言分支收口）。 */
    private static final class RecordingTagNode extends TagNode {
        private final List<String> n1Sees;
        private final List<String> n2Sees;
        private final List<String> executed;

        RecordingTagNode(List<String> n1Sees, List<String> n2Sees, List<String> executed) {
            this.n1Sees = n1Sees;
            this.n2Sees = n2Sees;
            this.executed = executed;
        }

        @Override
        public void onMsg(INodeContext ctx) {
            List<String> sink = "n1".equals(ctx.getNodeId()) ? n1Sees : n2Sees;
            sink.addAll(ctx.state().outputs().keySet());
            executed.add(ctx.getNodeId());
            super.onMsg(ctx);
        }
    }

    /** 按 {@code data.ports} 声明的端口集合一次性 fan-out。 */
    private static final class ForkNode implements RuleNode {
        @Override
        @SuppressWarnings("unchecked")
        public void onMsg(INodeContext ctx) {
            Object portsRaw = ctx.nodeConfig().get("ports");
            Set<String> ports = new LinkedHashSet<>();
            if (portsRaw instanceof Collection) {
                for (Object p : (Collection<Object>) portsRaw) {
                    ports.add(String.valueOf(p));
                }
            }
            ctx.next(ports);
        }
    }

    /** Loop 子链体：读迭代作用域 {@code item}，×2 记录并 emit {@code {result}}。 */
    private static final class EachNode implements RuleNode {
        private final List<Integer> doubled;

        EachNode(List<Integer> doubled) {
            this.doubled = doubled;
        }

        @Override
        public void onMsg(INodeContext ctx) {
            int item = ((Number) ctx.state().read(WorkflowConstants.CURRENT_LOCALS, "item")).intValue();
            int result = item * 2;
            doubled.add(result);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", result);
            ctx.emit(out);
            ctx.next();
        }
    }

    // ================= 支撑类型 =================

    private static final class MapRegistry implements INodeRegistry {
        private final Map<String, RuleNode> nodes;

        MapRegistry(Map<String, RuleNode> nodes) {
            this.nodes = nodes;
        }

        @Override
        public RuleNode get(String type) throws NodeException {
            RuleNode node = nodes.get(type);
            if (node == null) {
                throw new NodeException("测试注册表缺少类型: " + type, true);
            }
            return node;
        }

        @Override
        public <T> T get(String type, Class<T> requireClazz) throws NodeException {
            throw new NodeException("不支持按类型获取: " + type, true);
        }
    }

    /** 不落盘的链存储：装载主链并登记内联子链（Loop blocks），供执行器按 ID 取用。 */
    private static final class TestChainStore implements IChainStore {
        private final WorkflowSchemaLoader loader;
        private final Map<String, RuleChain> chains = new LinkedHashMap<>();

        TestChainStore(WorkflowSchemaLoader loader) {
            this.loader = loader;
        }

        @Override
        public RuleChain registerChain(Object chainData) throws NodeException {
            RuleChain chain = loader.load(chainData);
            registerRecursively(chain);
            return chain;
        }

        private void registerRecursively(RuleChain chain) {
            chains.putIfAbsent(chain.getId(), chain);
            for (RuleChain sub : chain.subChains().values()) {
                registerRecursively(sub);
            }
        }

        @Override
        public RuleChain deleteChain(String chainId) {
            return chains.remove(chainId);
        }

        @Override
        public Object getChainRawData(String chainId) {
            return null;
        }

        @Override
        public RuleChain getChain(String chainId) {
            return chains.get(chainId);
        }

        @Override
        public Collection<Object> getChains() {
            return Collections.emptyList();
        }
    }

    /** 错误捕获回调：同线程执行下无需批次令牌，只用于断言执行未走 {@code onError}。 */
    private static final class ErrorCallback implements ExecutionCallback {
        private volatile Throwable error;

        @Override
        public void onComplete() {
            // 同线程执行，收口与否由链上节点产出自证
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onFanOut(int branches) {
            // 同线程执行，无需批次令牌
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
