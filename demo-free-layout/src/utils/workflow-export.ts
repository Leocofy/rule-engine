/**
 * 导出前后端契约对齐：把 FlowGram document.toJSON() 的结果规整成后端 WorkflowSchema 可接受的形态。
 *
 * 关键点：后端 WorkflowSchemaLoader 要求每条边都带非空 sourcePortID（作为节点输出端口，
 * 与 INodeContext.PORT_DEFAULT / 各端口路由对齐）。而 FlowGram 的边序列化在源端口为空时会
 * 删除 sourcePortID 字段（见 free-layout-core 的 edge toJSON），导致 start -> end 这类串联边缺端口。
 * 这里统一补默认端口 "default"，已有端口（条件分支等）保持原值。
 */

/** 与引擎 INodeContext.PORT_DEFAULT 对齐 */
export const DEFAULT_SOURCE_PORT = 'default';

type AnyRecord = Record<string, unknown>;

/** 端口是否为有效非空值（保留数字 0 之类的合法 id 语义，只把 undefined/null/空串视为缺失） */
const hasPort = (value: unknown): boolean => value !== undefined && value !== null && value !== '';

const fixEdge = (edge: unknown): unknown => {
  if (!edge || typeof edge !== 'object') {
    return edge;
  }
  const e = edge as AnyRecord;
  return { ...e, sourcePortID: hasPort(e.sourcePortID) ? e.sourcePortID : DEFAULT_SOURCE_PORT };
};

const fixEdges = (edges: unknown): unknown => (Array.isArray(edges) ? edges.map(fixEdge) : edges);

/** 递归处理节点：Loop 等容器的内联子图 edges / blocks 同样补端口 */
const fixNode = (node: unknown): unknown => {
  if (!node || typeof node !== 'object') {
    return node;
  }
  const n = node as AnyRecord;
  const out: AnyRecord = { ...n };
  if (n.edges !== undefined) {
    out.edges = fixEdges(n.edges);
  }
  if (n.blocks !== undefined) {
    out.blocks = Array.isArray(n.blocks) ? n.blocks.map(fixNode) : n.blocks;
  }
  return out;
};

/**
 * 返回一份补齐 sourcePortID 的文档副本（不修改入参）。
 * 顶层 edges 与所有节点（含 Loop 子图）的 edges 都会被处理。
 */
export function normalizeWorkflowForBackend<T extends AnyRecord>(doc: T): T {
  const out: AnyRecord = { ...doc };
  if (doc.edges !== undefined) {
    out.edges = fixEdges(doc.edges);
  }
  if (Array.isArray(doc.nodes)) {
    out.nodes = doc.nodes.map(fixNode);
  }
  return out as T;
}
