/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { useCallback } from 'react';

import { DockedPanelLayer } from '@flowgram.ai/panel-manager-plugin';
import {
  EditorRenderer,
  FreeLayoutEditorProvider,
  useClientContext,
} from '@flowgram.ai/free-layout-editor';

import '@flowgram.ai/free-layout-editor/index.css';
import './styles/index.css';
import { nodeRegistries } from './nodes';
import type { FlowDocumentJSON } from './typings';
import { useEditorProps } from './hooks';
import { createNodeWithReadableId } from './utils';
import { FlowGramToolbar } from './components/flowgram-toolbar';
import { LeftPalette, FLOWGRAM_NODE_MIME } from './components/left-palette';

/**
 * 空白初始文档：仅含一个 start 与一个 end（工具栏可加载/新建覆盖）。
 * 不再使用 demo 的示例数据（http/llm/comment 等）。
 */
const emptyInitialData: FlowDocumentJSON = {
  nodes: [
    {
      id: 'start',
      type: 'start',
      meta: { position: { x: 120, y: 220 } },
      data: {
        title: '开始',
        outputs: { type: 'object', properties: {} },
      },
    },
    {
      id: 'end',
      type: 'end',
      meta: { position: { x: 480, y: 220 } },
      data: { title: '结束' },
    },
  ],
  edges: [],
};

/** 画布区域：承载左侧面板拖入的节点（onDrop 按落点坐标创建） */
const CanvasArea = () => {
  const ctx = useClientContext();

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      const type = e.dataTransfer.getData(FLOWGRAM_NODE_MIME);
      if (!type) {
        return;
      }
      // 落点客户端坐标 → 画布文档坐标
      const position = ctx.playground.config.getPosFromMouseEvent({
        clientX: e.clientX,
        clientY: e.clientY,
      });
      const registry = nodeRegistries.find((r) => r.type === type);
      const nodeJSON = registry?.onAdd?.(ctx);
      createNodeWithReadableId(ctx.document, type, position, nodeJSON);
    },
    [ctx],
  );

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
  }, []);

  return (
    <div className="demo-container" onDrop={handleDrop} onDragOver={handleDragOver}>
      <DockedPanelLayer>
        <EditorRenderer className="demo-editor" />
      </DockedPanelLayer>
    </div>
  );
};

export const Editor = () => {
  const editorProps = useEditorProps(emptyInitialData, nodeRegistries);
  return (
    <div className="doc-free-feature-overview">
      <FreeLayoutEditorProvider {...editorProps}>
        <div className="fg-shell">
          <FlowGramToolbar />
          <div className="fg-app">
            <LeftPalette />
            <div className="fg-canvas">
              <CanvasArea />
            </div>
          </div>
        </div>
      </FreeLayoutEditorProvider>
    </div>
  );
};
