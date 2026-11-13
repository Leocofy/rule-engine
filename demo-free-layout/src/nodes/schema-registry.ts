/**
 * 前端节点 schema 注册表：节点“长什么样”由前端维护，后端只负责可用节点清单与保存校验。
 *
 * 键为节点 type；未登记的 type 由通用表单回退为空对象 schema。
 */

import type { JsonSchema } from '../typings';

export interface NodeSchemaEntry {
  inputs: JsonSchema;
  outputs?: JsonSchema;
}

const emptyObjectSchema = (): JsonSchema => ({
  type: 'object',
  properties: {},
});

const conditionSchema: JsonSchema = {
  type: 'object',
  properties: {
    conditions: {
      type: 'array',
      title: '条件列表',
      items: {
        type: 'object',
        properties: {
          key: { type: 'string', title: '端口' },
          value: {
            type: 'object',
            properties: {
              left: {
                type: 'object',
                title: '左值',
                extra: { formComponent: 'flow-value', weak: true },
              },
              operator: {
                type: 'string',
                title: '操作符',
                enum: [
                  'eq',
                  'neq',
                  'gt',
                  'gte',
                  'lt',
                  'lte',
                  'in',
                  'nin',
                  'contains',
                  'not_contains',
                  'is_empty',
                  'is_not_empty',
                  'is_true',
                  'is_false',
                ],
              },
              right: {
                type: 'object',
                title: '右值',
                extra: { formComponent: 'flow-value', weak: true },
              },
            },
            required: ['operator'],
          },
        },
        required: ['key', 'value'],
      },
      extra: { formComponent: 'condition-list', index: 0 },
    },
  },
  required: ['conditions'],
};

const loopSchema: JsonSchema = {
  type: 'object',
  properties: {
    loopFor: {
      type: 'array',
      title: '循环集合',
      items: { type: 'object', properties: {} },
      extra: { formComponent: 'flow-value', valueType: 'array', index: 0 },
    },
    loopOutputs: {
      type: 'object',
      title: '循环产出',
      additionalProperties: { type: 'object', properties: {} },
      extra: { formComponent: 'key-value', index: 1 },
    },
  },
  required: ['loopFor'],
};

const scriptSchema: JsonSchema = {
  type: 'object',
  properties: {
    script: {
      type: 'object',
      title: '脚本',
      properties: {
        language: {
          type: 'string',
          title: '语言',
          enum: ['javascript'],
          default: 'javascript',
        },
        content: {
          type: 'string',
          title: '脚本内容',
        },
      },
      required: ['content'],
    },
  },
  required: ['script'],
};

const variableSchema: JsonSchema = {
  type: 'object',
  properties: {
    assign: {
      type: 'array',
      title: '变量赋值',
      items: {
        type: 'object',
        properties: {
          operator: {
            type: 'string',
            title: '操作',
            enum: ['declare', 'set'],
            default: 'declare',
          },
          left: { type: 'string', title: '变量名' },
          right: {
            type: 'object',
            title: '值',
            extra: { formComponent: 'flow-value' },
          },
        },
        required: ['left', 'right'],
      },
      extra: { formComponent: 'assign-list', index: 0 },
    },
  },
  required: ['assign'],
};

const multiConditionSchema: JsonSchema = {
  type: 'object',
  properties: {
    branch: {
      type: 'array',
      title: '条件分支',
      items: {
        type: 'object',
        properties: {
          logic: {
            type: 'string',
            title: '组合逻辑',
            enum: ['and', 'or'],
            default: 'and',
          },
          conditions: {
            type: 'array',
            title: '条件',
            items: {
              type: 'object',
              properties: {
                key: { type: 'string', title: '条件 ID' },
                value: {
                  type: 'object',
                  properties: {
                    left: {
                      type: 'object',
                      title: '左值',
                      extra: { formComponent: 'flow-value', weak: true },
                    },
                    operator: {
                      type: 'string',
                      title: '操作符',
                      enum: [
                        'eq',
                        'neq',
                        'gt',
                        'gte',
                        'lt',
                        'lte',
                        'in',
                        'nin',
                        'contains',
                        'not_contains',
                        'is_empty',
                        'is_not_empty',
                        'is_true',
                        'is_false',
                      ],
                    },
                    right: {
                      type: 'object',
                      title: '右值',
                      extra: { formComponent: 'flow-value', weak: true },
                    },
                  },
                  required: ['operator'],
                },
              },
              required: ['key', 'value'],
            },
          },
        },
        required: ['logic', 'conditions'],
      },
      extra: { formComponent: 'multi-condition-list', index: 0 },
    },
  },
  required: ['branch'],
};

export const nodeSchemas: Record<string, NodeSchemaEntry> = {
  start: { inputs: emptyObjectSchema(), outputs: emptyObjectSchema() },
  end: { inputs: emptyObjectSchema(), outputs: emptyObjectSchema() },
  condition: { inputs: conditionSchema, outputs: emptyObjectSchema() },
  loop: { inputs: loopSchema, outputs: emptyObjectSchema() },
  llm: { inputs: emptyObjectSchema() },
  http: { inputs: emptyObjectSchema() },
  code: { inputs: scriptSchema, outputs: emptyObjectSchema() },
  variable: { inputs: variableSchema, outputs: emptyObjectSchema() },
  comment: { inputs: emptyObjectSchema() },
  continue: { inputs: emptyObjectSchema() },
  break: { inputs: emptyObjectSchema() },
  'multi-condition': { inputs: multiConditionSchema, outputs: emptyObjectSchema() },
  'block-start': { inputs: emptyObjectSchema() },
  'block-end': { inputs: emptyObjectSchema() },
};

export function getNodeSchema(type: string): NodeSchemaEntry | undefined {
  return nodeSchemas[type];
}

/** 收集全量输入 schema，保存/校验时作为一次性验证上下文提交给后端。 */
export function collectNodeSchemas(): Record<string, JsonSchema> {
  const result: Record<string, JsonSchema> = {};
  Object.entries(nodeSchemas).forEach(([type, entry]) => {
    result[type] = entry.inputs;
  });
  return result;
}