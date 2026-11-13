/**
 * FlowGram 设计器后端适配层 —— 对接 cmp 自动装配的 `/flowgram/*` 接口。
 *
 * 开发环境通过 rsbuild devServer 代理 `/flowgram` → 后端（见 rsbuild.config.ts）；
 * 生产环境前端与后端同源，直接走相对路径。
 */

/** 节点库单个节点定义（`GET /flowgram/nodes` 元素） */
export interface FlowgramNodeDefinition {
  type: string;
  title: string;
  category: string;
  order: number;
  description: string;
  version: string;
  flowNodeType: string;
  /** 输入 JSON Schema（业务节点为参数定义；控制节点可能为空对象） */
  inputs?: unknown;
  /** 输出 JSON Schema */
  outputs?: unknown;
}

/** `GET /flowgram/nodes` 响应 */
export interface FlowgramNodesResponse {
  nodes: FlowgramNodeDefinition[];
  categories: string[];
}

/** `GET /flowgram/workflow` 摘要元素 */
export interface FlowgramWorkflowSummary {
  id: string;
  name: string;
  nodeCount: number;
  edgeCount: number;
}

/** `POST /flowgram/workflow` 响应 */
export interface FlowgramSaveResult {
  saved: boolean;
  chainId: string;
  chainName: string;
  savedAt: number;
}

const BASE = '/flowgram';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!resp.ok) {
    let reason = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      if (body && (body.message || body.error)) {
        reason = body.message || body.error;
      }
    } catch {
      /* ignore parse error */
    }
    throw new Error(reason);
  }
  return (await resp.json()) as T;
}

/** 节点库 */
export function fetchNodes(): Promise<FlowgramNodesResponse> {
  return request<FlowgramNodesResponse>('/nodes');
}

/** 工作流（规则链）摘要列表 */
export function fetchWorkflows(): Promise<FlowgramWorkflowSummary[]> {
  return request<FlowgramWorkflowSummary[]>('/workflow');
}

/** 读取指定链的原始 WorkflowSchema */
export function fetchWorkflow(chainId: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/workflow/${encodeURIComponent(chainId)}`);
}

/** 保存（覆盖同名链）；schemas 仅用于本次校验，后端不缓存、不落库 */
export function saveWorkflow(
  doc: Record<string, unknown>,
  schemas?: Record<string, unknown>,
  id?: string,
): Promise<FlowgramSaveResult> {
  return request<FlowgramSaveResult>('/workflow', {
    method: 'POST',
    body: JSON.stringify({ workflow: doc, schemas, id }),
  });
}

/** 删除指定链 */
export function deleteWorkflow(chainId: string): Promise<{ deleted: boolean; chainId: string }> {
  return request<{ deleted: boolean; chainId: string }>(
    `/workflow/${encodeURIComponent(chainId)}`,
    { method: 'DELETE' },
  );
}

/** 装载期校验已移除：官方流式测试运行面板自带校验/执行 */
