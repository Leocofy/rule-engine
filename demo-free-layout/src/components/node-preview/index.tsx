/**
 * 节点配置实时预览：展示当前节点的表单值（FlowGram data 配置），并随编辑实时更新。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';

import { useNodeRender, type WorkflowNodeEntity } from '@flowgram.ai/free-layout-editor';
import { Button, Toast, Typography } from '@douyinfe/semi-ui';

import { featureFlags } from '../../config/features';

export interface NodeConfigPreviewProps {
  node: WorkflowNodeEntity;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

export const NodeConfigPreview = ({ node }: NodeConfigPreviewProps) => {
  const form = useNodeRender(node).form;
  const [config, setConfig] = useState<Record<string, unknown>>(() =>
    asRecord(form?.values ?? node.toJSON().data)
  );
  const [updatedAt, setUpdatedAt] = useState<number>(() => Date.now());

  useEffect(() => {
    const sync = () => {
      setConfig(asRecord(form ? form.values : node.toJSON().data));
      setUpdatedAt(Date.now());
    };
    sync();
    const disposable = form?.onFormValuesChange(sync);
    return () => disposable?.dispose();
  }, [form, node]);

  const json = useMemo(() => JSON.stringify(config, null, 2), [config]);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(json);
      Toast.success('节点配置已复制');
    } catch {
      Toast.error('复制失败，请手动选择文本');
    }
  }, [json]);

  if (!featureFlags.nodeConfigPreview) {
    return null;
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        padding: 12,
        boxSizing: 'border-box',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
        <div style={{ minWidth: 0 }}>
          <Typography.Text strong ellipsis style={{ maxWidth: 240 }}>
            {String(config.title ?? node.id)}
          </Typography.Text>
          <Typography.Text type="tertiary" size="small" style={{ marginLeft: 8 }}>
            {node.flowNodeType}
          </Typography.Text>
          <div>
            <Typography.Text type="tertiary" size="small">
              实时配置 · {new Date(updatedAt).toLocaleTimeString()}
            </Typography.Text>
          </div>
        </div>
        <Button size="small" onClick={handleCopy}>
          复制 JSON
        </Button>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', marginTop: 8 }}>
        <pre
          style={{
            margin: 0,
            padding: 12,
            background: '#f7f8fa',
            border: '1px solid rgba(6, 7, 9, 0.08)',
            borderRadius: 8,
            fontSize: 12,
            lineHeight: 1.6,
          }}
        >
          {json}
        </pre>
      </div>
    </div>
  );
};