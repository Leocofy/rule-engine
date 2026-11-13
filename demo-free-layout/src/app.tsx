/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { createRoot } from 'react-dom/client';
import { unstableSetCreateRoot } from '@flowgram.ai/form-materials';

import { Editor } from './editor';
import { setNodeRegistries } from './nodes';
import { buildRegistries } from './nodes/build-registries';
import { fetchNodes } from './api/flowgram';

/**
 * React 18/19 polyfill for form-materials
 */
unstableSetCreateRoot(createRoot);

const container = document.getElementById('root')!;
const root = createRoot(container);

root.render(
  <div style={{ padding: 32, fontFamily: 'sans-serif', color: '#666' }}>加载节点目录…</div>,
);

/**
 * 应用启动流程：
 *  1. 从后端 `/flowgram/nodes` 拉取节点目录；
 *  2. 构建目录驱动的 FlowGram 注册表并替换默认注册表；
 *  3. 渲染编辑器。若后端不可达，则回退到 demo 内置节点，保证界面仍可运行。
 */
async function bootstrap(): Promise<void> {
  try {
    const response = await fetchNodes();
    setNodeRegistries(buildRegistries(response));
  } catch (err) {
    console.warn('[flowgram] 节点目录加载失败，回退到内置 demo 节点：', err);
  }
  root.render(<Editor />);
}

bootstrap();
