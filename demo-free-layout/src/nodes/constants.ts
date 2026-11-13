/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

export enum WorkflowNodeType {
  Start = 'start',
  End = 'end',
  LLM = 'llm',
  HTTP = 'http',
  Code = 'code',
  Variable = 'variable',
  Condition = 'condition',
  MultiCondition = 'multi-condition',
  Loop = 'loop',
  BlockStart = 'block-start',
  BlockEnd = 'block-end',
  Comment = 'comment',
  Continue = 'continue',
  Break = 'break',
}

/** 节点类型 → 中文展示名（用于仅显示类型标识的面板） */
export const NODE_TYPE_LABELS: Record<string, string> = {
  [WorkflowNodeType.Start]: '开始',
  [WorkflowNodeType.End]: '结束',
  [WorkflowNodeType.LLM]: '大模型',
  [WorkflowNodeType.HTTP]: 'HTTP 请求',
  [WorkflowNodeType.Code]: '代码',
  [WorkflowNodeType.Variable]: '变量赋值',
  [WorkflowNodeType.Condition]: '条件',
  [WorkflowNodeType.MultiCondition]: '多条件',
  [WorkflowNodeType.Loop]: '循环',
  [WorkflowNodeType.BlockStart]: '子画布开始',
  [WorkflowNodeType.BlockEnd]: '子画布结束',
  [WorkflowNodeType.Comment]: '注释',
  [WorkflowNodeType.Continue]: '继续',
  [WorkflowNodeType.Break]: '中断',
  group: '分组',
};
