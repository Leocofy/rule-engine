/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { FlowNodeRegistry } from '../typings';
import { VariableNodeRegistry } from './variable';
import { StartNodeRegistry } from './start';
import { LoopNodeRegistry } from './loop';
import { LLMNodeRegistry } from './llm';
import { HTTPNodeRegistry } from './http';
import { GroupNodeRegistry } from './group';
import { EndNodeRegistry } from './end';
import { ContinueNodeRegistry } from './continue';
import { ConditionNodeRegistry } from './condition';
import { CommentNodeRegistry } from './comment';
import { CodeNodeRegistry } from './code';
import { BreakNodeRegistry } from './break';
import { BlockStartNodeRegistry } from './block-start';
import { BlockEndNodeRegistry } from './block-end';
import { MultiConditionNodeRegistry } from "./multi-condition";
export { WorkflowNodeType } from './constants';

/**
 * 节点注册表。默认使用 demo 内置节点（离线回退），
 * 应用启动时若后端 `/flowgram/nodes` 目录加载成功，会通过 setNodeRegistries 替换为目录驱动的注册表。
 * 使用 `export let` + setter 以便导入方（editor / node-list）通过 ES live-binding 读取到最新值。
 */
export let nodeRegistries: FlowNodeRegistry[] = [
  ConditionNodeRegistry,
  StartNodeRegistry,
  EndNodeRegistry,
  LLMNodeRegistry,
  LoopNodeRegistry,
  CommentNodeRegistry,
  BlockStartNodeRegistry,
  BlockEndNodeRegistry,
  HTTPNodeRegistry,
  CodeNodeRegistry,
  ContinueNodeRegistry,
  BreakNodeRegistry,
  VariableNodeRegistry,
  GroupNodeRegistry,
  MultiConditionNodeRegistry,
];

/** 动态替换节点注册表（后端目录驱动） */
export function setNodeRegistries(list: FlowNodeRegistry[]): void {
  nodeRegistries = list;
}
