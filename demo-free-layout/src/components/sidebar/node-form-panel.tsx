/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { useCallback, useEffect, useState, startTransition } from 'react';

import {
  PlaygroundEntityContext,
  useRefresh,
  useClientContext,
} from '@flowgram.ai/free-layout-editor';
import { Button } from '@douyinfe/semi-ui';

import { FlowNodeMeta } from '../../typings';
import { featureFlags } from '../../config/features';
import { useNodeFormPanel } from '../../plugins/panel-manager-plugin/hooks';
import { IsSidebarContext } from '../../context';
import { NodeConfigPreview } from '../node-preview';
import { SidebarNodeRenderer } from './sidebar-node-renderer';

export type NodeFormPanelMode = 'form' | 'preview';

export interface NodeFormPanelProps {
  nodeId: string;
  /** 打开时的初始视图；默认表单。 */
  mode?: NodeFormPanelMode;
}

export const NodeFormPanel: React.FC<NodeFormPanelProps> = ({
  nodeId,
  mode: initialMode = 'form',
}) => {
  const previewEnabled = featureFlags.nodeConfigPreview;
  const [mode, setMode] = useState<NodeFormPanelMode>(previewEnabled ? initialMode : 'form');
  const { selection, playground, document } = useClientContext();
  const refresh = useRefresh();
  const { close: closePanel } = useNodeFormPanel();
  useEffect(() => {
    setMode(previewEnabled ? initialMode : 'form');
  }, [nodeId, initialMode, previewEnabled]);
  const activeMode: NodeFormPanelMode = previewEnabled ? mode : 'form';
  const handleClose = useCallback(() => {
    // Sidebar delayed closing
    startTransition(() => {
      closePanel();
    });
  }, []);
  const node = document.getNode(nodeId);
  const sidebarDisabled = node?.getNodeMeta<FlowNodeMeta>()?.sidebarDisabled === true;
  /**
   * Listen readonly
   */
  useEffect(() => {
    const disposable = playground.config.onReadonlyOrDisabledChange(() => {
      handleClose();
      refresh();
    });
    return () => disposable.dispose();
  }, [playground]);
  /**
   * Listen selection
   */
  useEffect(() => {
    const toDispose = selection.onSelectionChanged(() => {
      /**
       * 如果没有选中任何节点，则自动关闭侧边栏
       * If no node is selected, the sidebar is automatically closed
       */
      if (selection.selection.length === 0) {
        handleClose();
      } else if (selection.selection.length === 1 && selection.selection[0] !== node) {
        handleClose();
      }
    });
    return () => toDispose.dispose();
  }, [selection, node, handleClose]);
  /**
   * Close when node disposed
   */
  useEffect(() => {
    if (node) {
      const toDispose = node.onDispose(() => {
        closePanel();
      });
      return () => toDispose.dispose();
    }
    return () => {};
  }, [node, sidebarDisabled, handleClose]);
  /**
   * Cloze when sidebar disabled
   */
  useEffect(() => {
    if (!node || sidebarDisabled || playground.config.readonly) {
      handleClose();
    }
  }, [node, sidebarDisabled, playground.config.readonly]);

  if (!node || sidebarDisabled || playground.config.readonly) {
    return null;
  }

  return (
    <IsSidebarContext.Provider value={true}>
      <PlaygroundEntityContext.Provider key={node.id} value={node}>
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '8px 12px',
              borderBottom: '1px solid rgba(6, 7, 9, 0.08)',
              flex: '0 0 auto',
            }}
          >
            <Button
              size="small"
              type={mode === 'form' ? 'primary' : 'tertiary'}
              theme={mode === 'form' ? 'solid' : 'borderless'}
              onClick={() => setMode('form')}
            >
              表单
            </Button>
            {previewEnabled && (
              <Button
                size="small"
                type={mode === 'preview' ? 'primary' : 'tertiary'}
                theme={mode === 'preview' ? 'solid' : 'borderless'}
                onClick={() => setMode('preview')}
              >
                配置预览
              </Button>
            )}
          </div>
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
            {activeMode === 'form' ? (
              <SidebarNodeRenderer node={node} />
            ) : (
              <NodeConfigPreview node={node} />
            )}
          </div>
        </div>
      </PlaygroundEntityContext.Provider>
    </IsSidebarContext.Provider>
  );
};
