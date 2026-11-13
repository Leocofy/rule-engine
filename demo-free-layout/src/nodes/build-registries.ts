/**
 * 由后端 `GET /flowgram/nodes` 目录动态构建 FlowGram 节点注册表。
 *
 * - 控制流节点（start/end/condition/loop）复用 demo 现成的专用 registry（含端口/分支/子画布逻辑）；
 * - 业务节点统一走通用模式：省略 `formMeta`（由 `getNodeDefaultRegistry` 兜底为 `defaultFormMeta`），
 *   `onAdd` 生成 `{ id, type, data: { title, inputs, outputs, inputsValues } }`，
 *   其中 `inputs`/`outputs` 直接取目录里的 JSON Schema，供表单与变量引擎渲染。
 */

import { nanoid } from 'nanoid';

import type { FlowNodeRegistry, FlowNodeMeta, JsonSchema } from '../typings';
import type { WorkflowNodeMeta } from '@flowgram.ai/free-layout-editor';
import type { FlowgramNodeDefinition, FlowgramNodesResponse } from '../api/flowgram';

import { StartNodeRegistry } from './start';
import { EndNodeRegistry } from './end';
import { ConditionNodeRegistry } from './condition';
import { LoopNodeRegistry } from './loop';
import { BlockStartNodeRegistry } from './block-start';
import { BlockEndNodeRegistry } from './block-end';
import { CommentNodeRegistry } from './comment';
import { GroupNodeRegistry } from './group';
import { CodeNodeRegistry } from './code';
import { VariableNodeRegistry } from './variable';
import { MultiConditionNodeRegistry } from './multi-condition';
import { getNodeSchema } from './schema-registry';

/** 始终注册的基础设施节点：loop 子画布(block-start/end) 及 comment/group */
const INFRA_REGISTRIES: FlowNodeRegistry[] = [
  BlockStartNodeRegistry,
  BlockEndNodeRegistry,
  CommentNodeRegistry,
  GroupNodeRegistry,
];

/** 后端目录中按类型缓存，供载入（fromNodeJSON）时补全 inputs/outputs schema */
const catalogByType = new Map<string, FlowgramNodeDefinition>();

/** 目录分类顺序缓存（供左侧面板分组） */
let categoriesCache: string[] = [];

/** 不在拖拽面板中出现的类型（start/end 由系统自动维护） */
const PALETTE_HIDDEN_TYPES = new Set(['start', 'end']);

/** 读取某类型在目录中的定义（含 inputs/outputs schema） */
export function getCatalogNode(type: string): FlowgramNodeDefinition | undefined {
  return catalogByType.get(type);
}

/** 左侧面板数据：按分类分组的可拖拽节点 */
export interface PaletteGroup {
  category: string;
  nodes: FlowgramNodeDefinition[];
}

/**
 * 返回按分类分组的可添加节点。
 * 分类顺序取自后端 `categories`；未列出的分类追加在后。start/end 被隐藏。
 */
export function getPaletteGroups(): PaletteGroup[] {
  const groups: PaletteGroup[] = [];
  const used = new Set<string>();

  const pushCategory = (category: string) => {
    const list = [...catalogByType.values()].filter(
      (n) => (n.category || 'other') === category && !PALETTE_HIDDEN_TYPES.has(n.type),
    );
    if (list.length === 0) {
      return;
    }
    list.forEach((n) => used.add(n.type));
    groups.push({ category, nodes: list });
  };

  categoriesCache.forEach((c) => pushCategory(c));
  // 兜底：目录中出现的、未列入 categories 的分类
  const extraCategories = [...catalogByType.values()]
    .map((n) => n.category || 'other')
    .filter((c) => !categoriesCache.includes(c));
  [...new Set(extraCategories)].forEach((c) => pushCategory(c));

  return groups;
}

/** 有本地专用 formMeta 的节点类型；优先复用 demo 注册表，保留自定义表单/端口 */
const LOCAL_REGISTRIES: Record<string, FlowNodeRegistry> = {
  start: StartNodeRegistry,
  end: EndNodeRegistry,
  condition: ConditionNodeRegistry,
  loop: LoopNodeRegistry,
  code: CodeNodeRegistry,
  variable: VariableNodeRegistry,
  'multi-condition': MultiConditionNodeRegistry,
};

/** 空 JSON Schema（对象） */
function emptyObjectSchema() {
  return { type: 'object', properties: {} };
}

/** 为业务节点构建通用 registry */
function buildBusinessRegistry(definition: FlowgramNodeDefinition): FlowNodeRegistry {
  const meta: FlowNodeMeta = {
    size: { width: 360 },
    defaultExpanded: true,
  } as FlowNodeMeta & Partial<WorkflowNodeMeta>;

  return {
    type: definition.type,
    meta,
    info: {
      icon: '',
      description: definition.description || definition.title || '',
    },
    onAdd() {
      const schema = getNodeSchema(definition.type);
      return {
        id: `${definition.type}_${nanoid(5)}`,
        type: definition.type,
        data: {
          title: definition.title || definition.type,
          inputs: (schema?.inputs ?? definition.inputs ?? emptyObjectSchema()) as JsonSchema,
          outputs: (schema?.outputs ?? definition.outputs ?? emptyObjectSchema()) as JsonSchema,
          inputsValues: {},
        },
      };
    },
  };
}

/**
 * 主入口：把目录响应转换为 FlowGram 注册表数组。
 * 会缓存目录（含各节点 inputs/outputs schema）供载入补全。
 */
export function buildRegistries(response: FlowgramNodesResponse): FlowNodeRegistry[] {
  catalogByType.clear();
  categoriesCache = response.categories ?? [];
  const registries: FlowNodeRegistry[] = [];
  const seen = new Set<string>();

  (response.nodes ?? []).forEach((node) => {
    catalogByType.set(node.type, node);
    if (seen.has(node.type)) {
      return;
    }
    seen.add(node.type);

    const localRegistry = LOCAL_REGISTRIES[node.type];
    if (localRegistry) {
      registries.push(localRegistry);
    } else {
      registries.push(buildBusinessRegistry(node));
    }
  });

  // 追加基础设施节点（去重），保证 loop 子画布与 comment/group 正常渲染
  INFRA_REGISTRIES.forEach((infra) => {
    if (!seen.has(infra.type as string)) {
      seen.add(infra.type as string);
      registries.push(infra);
    }
  });

  return registries;
}
