package io.fire.rule.core.chain;

import io.fire.rule.core.config.INodeConfigBinder;
import io.fire.rule.core.config.IValueResolver;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.IMessageSink;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.NodeException;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 节点上下文默认实现：路由语义与取值解析（经注入的 {@link IValueResolver}）的真实落点。
 *
 * <p>统一 State 模型：节点通过 {@code emit(out)} 暂存产出、{@code next(port)} 投递；
 * 本类在投递时把产出写入命名空间 {@code outputs[nodeId]}（经 {@link RuleMessage#withState} 派生新信封），
 * 再沿既有路由逻辑派发。<b>投递/回跳/批次计数的语义与旧实现完全一致</b>，只是搬运对象由消息换成状态快照。
 *
 * <p><b>next 与 enqueueNext 的区别（关键）</b>：
 * <ul>
 *   <li>next —— 内存投递，不序列化，同 JVM 内流转</li>
 *   <li>enqueueNext —— 落队列，端口随状态序列化，可跨实例、可恢复</li>
 * </ul>
 */
public class SampleNodeContext implements INodeContext {

    private final ChainExecutor executor;
    private final RuleChain chain;
    private final String nodeId;
    private final RuleMessage inMsg;
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private final IMessageSink sink;
    private final Map<Class<?>, Object> capabilities;
    private final ExecutionCallback callback;
    private final ExecutionContext execCtx;
    private final IValueResolver valueResolver;
    private final INodeConfigBinder configBinder;
    /**
     * 是否对节点使用的端口做严格校验（端口必须出现在规则链该节点的出边中）。
     * 默认关闭，避免误伤依赖动态端口的节点；可在 {@code RuleEngine.Builder} 上开启。
     */
    private final boolean strictPortCheck;

    /**
     * 本次执行的出口状态：保证一个节点每次执行只产生一个出口。
     */
    enum RouteState {NONE, ROUTED, FAILED, ACKED}

    private final AtomicReference<RouteState> routeState = new AtomicReference<>(RouteState.NONE);
    /**
     * 节点是否把处理委派给了异步线程；委派后允许 {@code onMsg} 返回时暂时没有出口。
     */
    private volatile boolean asyncDelegated;

    private final InputsWriter inputsWriter;

    private final OutputsWriter outputsWriter;

    /**
     * 逃生舱（D1）：暂存的入口负载替换（{@link #replaceEntry}）。
     */
    private volatile Map<String, Object> stagedEntry;
    /**
     * 逃生舱（D1）：暂存的元数据增量（{@link #metaPut}），可累积多键。
     */
    private final Map<String, Object> stagedMeta = Collections.synchronizedMap(new LinkedHashMap<>());

    private SampleNodeContext(Builder b) {
        this.executor = b.executor;
        this.chain = b.chain;
        this.nodeId = b.nodeId;
        this.inMsg = b.msg;
        this.expressionEngine = b.expressionEngine;
        this.asyncExecutor = b.asyncExecutor;
        this.sink = b.sink;
        this.capabilities = b.capabilities != null ? b.capabilities : new HashMap<>();
        this.callback = b.execCtx.getCallback();
        this.execCtx = b.execCtx;
        this.valueResolver = b.valueResolver;
        this.configBinder = b.configBinder;
        this.strictPortCheck = b.strictPortCheck;
        this.inputsWriter = new InputsWriter();
        this.outputsWriter = new OutputsWriter();
    }

    /**
     * 本次执行的出口状态，供执行器在 onMsg 返回后做"出口缺失"检测。
     */
    RouteState routeState() {
        return routeState.get();
    }

    /**
     * 节点是否已把处理委派给异步线程。
     */
    boolean asyncDelegated() {
        return asyncDelegated;
    }

    private void markRoute(RouteState target, String action) {
        if (!routeState.compareAndSet(RouteState.NONE, target)) {
            throw new NodeException("节点 " + nodeId + " 重复产生出口（" + action
                    + "），当前状态=" + routeState.get(), true);
        }
    }

    /**
     * 端口校验：只对"节点确实声明了出边、却使用了未声明端口"的情况报错。
     * 无出边的末端节点允许用任意端口终止，避免误伤动态路由节点。
     */
    private void checkPorts(Set<String> ports) {
        if (!strictPortCheck) {
            return;
        }
        Map<String, List<String>> outgoing = chain.outgoing(nodeId);
        if (outgoing.isEmpty()) {
            return;
        }
        for (String port : ports) {
            if (port != null && !outgoing.containsKey(port)) {
                throw new NodeException("节点 " + nodeId + " 使用了未声明的端口 '" + port
                        + "'，可用端口=" + outgoing.keySet(), true);
            }
        }
    }

    @Override
    public void emit(Object output) {
        if (output instanceof Map) {
            ((Map<?, ?>) output).forEach((k, v) -> this.outputsWriter.write(k.toString(), v));
        } else {
            this.outputsWriter.write("result", output);
        }
    }

    /**
     * 本节点最近一次 {@link #emit} 的产出，供观测层构造 FINISHED delta。
     */
    public Object emittedOutput() {
        return this.outputsWriter;
    }

    @Override
    public void replaceEntry(Map<String, Object> entry) {
        this.stagedEntry = entry;
    }

    @Override
    public void metaPut(String key, Object value) {
        if (key != null) {
            stagedMeta.put(key, value);
        }
    }

    /**
     * 把暂存的入口替换 / 元数据增量 / 命名空间产出写入后的信封；三者皆无则原样透传
     * （等价于旧"节点自己改写 payload / metadata 后 tellNext"）。
     */
    private RuleMessage enriched() {
        return enrichedFrom(inMsg.getState());
    }

    /**
     * 以给定的 {@code base} 为基态叠加暂存产出；{@code base} 为进入态时等价 {@link #enriched()}。
     */
    private RuleMessage enrichedFrom(ExecutionState base) {
        ExecutionState st = base;
        if (stagedEntry != null) {
            st = st.withEntry(stagedEntry);
        }
        if (!stagedMeta.isEmpty()) {
            for (Map.Entry<String, Object> e : stagedMeta.entrySet()) {
                st = st.withMeta(e.getKey(), e.getValue());
            }
        }
        if (outputsWriter != null) {
            st = st.withOutput(nodeId, outputsWriter.outputs());
        }
        return st == inMsg.getState() ? inMsg : inMsg.withState(st);
    }

    @Override
    public void next(Set<String> ports) {
        if (ports == null || ports.isEmpty()) {
            return;
        }
        if (!isValid()) {
            return; // 批次已超时/取消，放弃处理
        }
        markRoute(RouteState.ROUTED, "next");
        checkPorts(ports);
        route(enriched(), ports);
    }

    @Override
    public void next(ExecutionState carry, Set<String> ports) {
        if (ports == null || ports.isEmpty()) {
            return;
        }
        if (!isValid()) {
            return;
        }
        markRoute(RouteState.ROUTED, "next(carry)");
        checkPorts(ports);
        route(enrichedFrom(carry == null ? inMsg.getState() : carry), ports);
    }

    /**
     * 派发主体：按端口统计分支、上报路由、逐个投递；无下游则回跳/收口。
     */
    private void route(RuleMessage msg, Set<String> ports) {
        execCtx.recordMessage(msg);

        // 先统计本次将派生出的分支总数，在开始派发前一次性声明。
        // 顺序很关键：必须在任何 dispatch 之前，否则先完成的分支会让批次提前收口。
        int totalTargets = 0;
        for (String port : ports) {
            totalTargets += chain.downstream(nodeId, port).size();
        }
        if (totalTargets > 1) {
            callback.onFanOut(totalTargets);
        }

        boolean anyRouted = false;
        Set<String> routedPorts = new LinkedHashSet<>();
        for (String port : ports) {
            if (chain.downstream(nodeId, port).isEmpty()) {
                continue;
            }
            anyRouted = true;
            routedPorts.add(port);
        }

        // 按端口逐个上报 + 派发：端口与下游一一对应，追踪时才能还原"哪条分支去了哪"。
        for (String port : routedPorts) {
            List<String> targets = chain.downstream(nodeId, port);
            executor.notifyRouted(chain, nodeId, msg, Collections.singleton(port), targets);
            for (String target : targets) {
                executor.dispatch(chain, target, msg, execCtx);
            }
        }

        // 没有任何下游接住这条端口 → 走到链路尽头。
        // 若当前处于嵌套链中，必须回跳到调用者链，否则子链结果会被静默吞掉。
        // 直接走回跳实现，避免再次触发 public output 的单出口校验。
        if (!anyRouted) {
            outputRecursive(msg, PORT_DEFAULT);
        }
    }

    @Override
    public void fail(Throwable t) {
        if (!isValid()) {
            return;
        }
        markRoute(RouteState.FAILED, "fail");
        RuleMessage msg = enriched();
        List<String> targets = chain.downstream(nodeId, PORT_EXCEPTION);
        if (targets.isEmpty()) {
            // 异常端口无人接管 → 整条消息判失败
            if (callback != null) {
                callback.onError(t != null ? t : new RuntimeException("node failed: " + nodeId));
            }
            return;
        }
        executor.notifyRouted(chain, nodeId, msg, Collections.singleton(PORT_EXCEPTION), targets);
        for (String target : targets) {
            executor.dispatch(chain, target, msg, execCtx);
        }
    }

    @Override
    public void retrySelf(long delayMs) {
        markRoute(RouteState.ROUTED, "retrySelf");
        executor.scheduleSelf(chain, nodeId, enriched(), execCtx, delayMs);
    }

    @Override
    public void enqueueNext(String port) {
        enqueueNext(Collections.singleton(port));
    }

    /**
     * 跨节点投递：端口随状态一起序列化，消费端据此继续路由。
     */
    public void enqueueNext(Set<String> ports) {
        if (!isValid() || ports == null || ports.isEmpty()) {
            return;
        }
        markRoute(RouteState.ROUTED, "enqueueNext");
        checkPorts(ports);
        RuleMessage msg = enriched();
        if (sink == null || sink.isLocal(chain.getId(), msg)) {
            // 未配置跨节点通道，或目标分区就在本实例：退化为本地投递，
            // 复用当前批次回调，避免转入无回调的 resume 导致批次挂到超时。
            route(msg, ports);
            return;
        }
        sink.send(chain.getId(), nodeId, msg, ports);
    }

    @Override
    public void input(String chainId) {
        if (!isValid()) {
            return;
        }
        // 循环检测与压栈必须使用同一组键：(当前链, 当前节点)
        if (execCtx.alreadyInStack(chain.getId(), nodeId)) {
            fail(new NodeException(
                    "检测到规则链循环引用: chain=" + chain.getId() + ", node=" + nodeId, true));
            return;
        }
        markRoute(RouteState.ROUTED, "input");
        RuleMessage msg = enriched();
        ExecutionContext childCtx = execCtx.copy();
        childCtx.pushToStack(chain.getId(), nodeId);   // 压栈：记住从哪来
        executor.execute(msg, chainId, childCtx);
    }

    @Override
    public void output(String port) {
        if (!isValid()) {
            return;
        }
        markRoute(RouteState.ROUTED, "output");
        outputRecursive(enriched(), port == null ? PORT_DEFAULT : port);
    }

    /**
     * 回跳实现：出栈拿到调用者，把状态送到<b>调用者节点的下游</b>。
     *
     * <p>注意不能 dispatch 到调用者节点本身——那会重新触发 input，形成死循环。
     * 若调用者节点在该端口下也没有下游，继续向上回溯（支持多层嵌套）。
     */
    private void outputRecursive(RuleMessage msg, String port) {
        execCtx.recordMessage(msg);
        String[] caller = execCtx.popFromStack();
        if (caller == null) {
            complete();                             // 已回到顶层链，确认完成
            return;
        }
        RuleChain callerChain = executor.getChain(caller[0]);
        if (callerChain == null) {
            complete();
            return;
        }
        List<String> targets = callerChain.downstream(caller[1], port);
        if (targets.isEmpty()) {
            outputRecursive(msg, port);             // 继续向上回溯
            return;
        }
        for (String target : targets) {
            executor.dispatch(callerChain, target, msg, execCtx);
        }
    }

    @Override
    public void ack() {
        if (!isValid()) {
            return;
        }
        markRoute(RouteState.ACKED, "ack");
        RuleMessage msg = enriched();
        execCtx.recordMessage(msg);
        complete();
    }

    private void complete() {
        if (callback != null) {
            callback.onComplete();
        }
    }

    @Override
    public Executor asyncExecutor() {
        asyncDelegated = true;
        return asyncExecutor;
    }

    @Override
    public IExpressionEngine expressionEngine() {
        return expressionEngine;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T capability(Class<T> type) {
        Object c = capabilities.get(type);
        return c == null ? null : (T) c;
    }

    @Override
    public NodeConfig nodeConfig() {
        return chain.nodeConfig(nodeId);
    }

    @Override
    public ExecutionState state() {
        return inMsg.getState();
    }

    @Override
    public Object inputs() {
        return inputsWriter;
    }

    @Override
    public <T> T getMetaData(String key, Class<T> type) {
        return state().metaValue(key, type);
    }

    @Override
    public ExecutionState runSubChain(String chainId, ExecutionState seed) {
        return executor.runSubChain(chainId, inMsg.withState(seed == null ? inMsg.getState() : seed));
    }

    @Override
    public Object resolveValue(Object value, IExpressionEngine expressionEngine) {
        return valueResolver.resolveOne(value, this, expressionEngine);
    }

    @Override
    public void withInput(String key, Object value) {
        inputsWriter.write(key, value);
    }

    @Override
    public <T> T config(Class<T> type) {
        T compiled = chain.compiledConfig(nodeId, type);
        if (compiled != null) {
            return compiled;
        }
        if (configBinder == null) {
            return null;
        }
        return configBinder.bind(chain.nodeConfig(nodeId), type);
    }

    @Override
    public boolean isValid() {
        return callback != null && callback.isValid();
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        protected ChainExecutor executor;
        protected RuleChain chain;
        protected String nodeId;
        protected RuleMessage msg;
        protected IExpressionEngine expressionEngine;
        protected Executor asyncExecutor;
        protected IMessageSink sink;
        protected Map<Class<?>, Object> capabilities;
        protected ExecutionContext execCtx;
        protected IValueResolver valueResolver;
        protected INodeConfigBinder configBinder;
        protected boolean strictPortCheck;

        public Builder executor(ChainExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder chain(RuleChain chain) {
            this.chain = chain;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder msg(RuleMessage msg) {
            this.msg = msg;
            return this;
        }

        public Builder expressionEngine(IExpressionEngine expressionEngine) {
            this.expressionEngine = expressionEngine;
            return this;
        }

        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        public Builder sink(IMessageSink sink) {
            this.sink = sink;
            return this;
        }

        public Builder capabilities(Class<?> clazz, Object capabilities) {
            if (this.capabilities == null) {
                this.capabilities = new HashMap<>();
            }
            this.capabilities.put(clazz, capabilities);
            return this;
        }

        public Builder capabilities(Map<Class<?>, Object> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder execCtx(ExecutionContext execCtx) {
            this.execCtx = execCtx;
            return this;
        }

        public Builder valueResolver(IValueResolver valueResolver) {
            this.valueResolver = valueResolver;
            return this;
        }

        public Builder configBinder(INodeConfigBinder configBinder) {
            this.configBinder = configBinder;
            return this;
        }

        public Builder strictPortCheck(boolean strictPortCheck) {
            this.strictPortCheck = strictPortCheck;
            return this;
        }

        public SampleNodeContext build() {
            return new SampleNodeContext(this);
        }
    }
}
