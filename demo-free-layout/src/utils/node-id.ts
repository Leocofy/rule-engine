/**
 * 节点 ID 生成：把 FlowGram 默认的随机 id（nanoid）换成按类型自增的可读 id，
 * 例如第一个条件节点是 condition_1、第二个是 condition_2。
 *
 * 后端用 nodeId 作为产出命名空间（outputs[nodeId]）与下游 ref 的 scope，
 * 可读且稳定的 id 让引用直观、可预测。
 */
import type {
  WorkflowDocument,
  WorkflowNodeEntity,
  WorkflowNodeJSON,
} from '@flowgram.ai/free-layout-editor';

/** 位置类型与 createWorkflowNodeByType 的 IPoint 兼容 */
interface NodePosition {
  x: number;
  y: number;
}

/** 把节点类型规整成 id 安全的基础串（仅保留字母/数字/下划线） */
const baseToken = (type: string | number): string => String(type).replace(/[^a-zA-Z0-9_]/g, '_');

/**
 * 依据当前文档已有的节点 id，返回该类型的下一个自增 id（从 1 起）。
 * 会跳过已被占用的序号，保证全局唯一（含循环等容器内子节点）。
 */
export const nextNodeId = (document: WorkflowDocument, type: string | number): string => {
  const base = baseToken(type);
  const taken = new Set(document.getAllNodes().map((node) => node.id));
  let index = 1;
  while (taken.has(`${base}_${index}`)) {
    index += 1;
  }
  return `${base}_${index}`;
};

/**
 * 创建节点并盖一个按类型自增的可读 id；若调用方已在 nodeJSON 里显式给了 id，则尊重原值不覆盖。
 * 参数顺序与 document.createWorkflowNodeByType 对齐，便于逐处替换。
 */
export const createNodeWithReadableId = (
  document: WorkflowDocument,
  type: string,
  position?: NodePosition,
  nodeJSON?: Partial<WorkflowNodeJSON>,
  parentID?: string
): WorkflowNodeEntity => {
  const json = nodeJSON ?? {};
  const id = json.id ?? nextNodeId(document, type);
  return document.createWorkflowNodeByType(type, position, { ...json, id }, parentID);
};
