/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { pluginReact } from '@rsbuild/plugin-react';
import { pluginLess } from '@rsbuild/plugin-less';
import { defineConfig } from '@rsbuild/core';

/**
 * 功能开关：节点配置预览仅开发环境开启，生产构建默认关闭。
 * 如需在生产临时排障，可显式传 FLOWGRAM_PREVIEW=true。
 */
const enableNodeConfigPreview =
  process.env.NODE_ENV !== 'production' || process.env.FLOWGRAM_PREVIEW === 'true';

export default defineConfig({
  plugins: [pluginReact(), pluginLess()],
  source: {
    define: {
      __FLOWGRAM_ENABLE_NODE_CONFIG_PREVIEW__: JSON.stringify(enableNodeConfigPreview),
    },
    entry: {
      index: './src/app.tsx',
    },
    /**
     * support inversify @injectable() and @inject decorators
     */
    decorators: {
      version: 'legacy',
    },
  },
  html: {
    title: 'demo-free-layout',
  },
  server: {
    /**
     * 开发环境代理：前端 `/flowgram/*`、`/api/*` → 后端（example, 端口 18080）
     * Development proxy: forward `/flowgram/*` and `/api/*` (FlowGram Runtime API) to the Spring Boot backend.
     */
    proxy: {
      '/flowgram': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
  tools: {
    rspack: {
      /**
       * ignore warnings from @coze-editor/editor/language-typescript
       */
      ignoreWarnings: [/Critical dependency: the request of a dependency is an expression/],
    },
  },
});
