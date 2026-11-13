package io.fire.rule.spring.flowgram.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 单节点报告；snapshots 保留该节点每次执行的输入/输出快照。 */
final class NodeReport {

    String id;
    volatile String status = "pending";
    volatile boolean terminated;
    volatile long startTime;
    volatile long endTime;
    volatile long timeCost;
    final List<Object> snapshots = new CopyOnWriteArrayList<>();
}