/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { nanoid } from 'nanoid';

import { WorkflowNodeType } from '../constants';
import { FlowNodeRegistry } from '../../typings';
import iconCode from '../../assets/icon-script.png';
import { formMeta } from './form-meta';

let index = 0;

const defaultCode = `// 节点输入可通过 params 读取，例如 const input = params.input;
// 返回值会写入本节点 outputs，供下游按名引用。
function main(params) {
  const input = params.input;
  const ret = {
    key0: input + input,
    key1: ["hello", "world"],
    key2: {
      key21: "hi"
    },
  };

  return ret;
}`;

export const CodeNodeRegistry: FlowNodeRegistry = {
  type: WorkflowNodeType.Code,
  info: {
    icon: iconCode,
    description: '执行脚本。',
  },
  meta: {
    size: {
      width: 360,
      height: 390,
    },
  },
  onAdd() {
    return {
      id: `code_${nanoid(5)}`,
      type: 'code',
      data: {
        title: `代码_${++index}`,
        inputsValues: {
          input: { type: 'constant', content: '' },
        },
        script: {
          language: 'javascript',
          content: defaultCode,
        },
        outputs: {
          type: 'object',
          properties: {
            key0: {
              type: 'string',
            },
            key1: {
              type: 'array',
              items: {
                type: 'string',
              },
            },
            key2: {
              type: 'object',
              properties: {
                key21: {
                  type: 'string',
                },
              },
            },
          },
        },
      },
    };
  },
  formMeta: formMeta,
};
