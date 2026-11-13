/**
 * FlowGram 设计器顶部工具栏 —— 对接后端 `/flowgram/*`：
 * 工作流列表 / 加载 / 新建 / 保存 / 删除。
 *
 * 该组件必须渲染在 FreeLayoutEditorProvider 内部（依赖 useClientContext 访问 document）。
 */

import { useCallback, useEffect, useState } from 'react';

import { Button, Input, Select, Space, Toast, Typography } from '@douyinfe/semi-ui';
import { useClientContext, useService } from '@flowgram.ai/free-layout-editor';

import { normalizeWorkflowForBackend } from '../../utils/workflow-export';
import { WorkflowRuntimeService } from '../../plugins/runtime-plugin/runtime-service';
import {
  deleteWorkflow,
  fetchWorkflow,
  fetchWorkflows,
  saveWorkflow,
  type FlowgramWorkflowSummary,
} from '../../api/flowgram';
import { collectNodeSchemas } from '../../nodes/schema-registry';

/** 生成合法链 id（用于后端 store 主键） */
function buildChainId(name: string): string {
  const base = (name || 'chain').trim().replace(/\s+/g, '_');
  return `${base || 'chain'}`;
}

export const FlowGramToolbar = () => {
  const ctx = useClientContext();
  const runtimeService = useService(WorkflowRuntimeService);
  const [options, setOptions] = useState<FlowgramWorkflowSummary[]>([]);
  const [name, setName] = useState('新建规则链');
  const [chainId, setChainId] = useState<string>('');
  const [status, setStatus] = useState<string>('');

  useEffect(() => {
    // 官方 Runtime API 需要工作流 ID；未保存的新画布也先用稳定 id，校验/执行时后端落库
    runtimeService.setWorkflowId(chainId || buildChainId(name));
  }, [chainId, name, runtimeService]);

  const reloadList = useCallback(async () => {
    try {
      setOptions(await fetchWorkflows());
    } catch (err) {
      console.warn('[flowgram] 加载工作流列表失败', err);
    }
  }, []);

  useEffect(() => {
    reloadList();
  }, [reloadList]);

  /** 导出当前文档为后端 WorkflowSchema（补 id / name，并为串联边补齐 sourcePortID） */
  const exportDoc = useCallback(
    () => ({
      id: chainId || buildChainId(name),
      name,
      ...normalizeWorkflowForBackend(ctx.document.toJSON() as unknown as Record<string, unknown>),
    }),
    [chainId, name, ctx],
  );

  const handleLoad = useCallback(
    async (id: string) => {
      try {
        const raw = await fetchWorkflow(id);
        // 历史 JSON 可能缺少 sourcePortID（旧版本保存），补齐后再 fromJSON，否则末端连线会被丢掉
        ctx.document.fromJSON(normalizeWorkflowForBackend(raw as Record<string, unknown>) as never);
        setChainId(String(raw.id ?? id));
        setName(String(raw.name ?? id));
        setStatus(`已载入：${raw.name ?? id}`);
      } catch (err) {
        Toast.error(`载入失败：${(err as Error).message}`);
      }
    },
    [ctx],
  );

  const handleNew = useCallback(() => {
    ctx.document.fromJSON({
      nodes: [
        { id: 'start', type: 'start', data: { title: '开始', outputs: { type: 'object', properties: {} } }, meta: { position: { x: 120, y: 200 } } },
        { id: 'end', type: 'end', data: { title: '结束' }, meta: { position: { x: 480, y: 200 } } },
      ],
      edges: [],
    } as never);
    setChainId('');
    setName('新建规则链');
    setStatus('已新建空白规则链');
  }, [ctx]);

  const persist = useCallback(
    async (silent = false) => {
      try {
        const result = await saveWorkflow(exportDoc(), collectNodeSchemas(), chainId || undefined);
        setChainId(result.chainId);
        setStatus(`保存成功：${result.chainName} @ ${new Date(result.savedAt).toLocaleTimeString()}`);
        if (!silent) {
          Toast.success('保存成功');
        }
        reloadList();
      } catch (err) {
        if (!silent) {
          Toast.error(`保存失败：${(err as Error).message}`);
        }
      }
    },
    [chainId, exportDoc, reloadList],
  );

  const handleSave = useCallback(() => persist(false), [persist]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void persist(true);
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [persist]);

  const handleDelete = useCallback(
    async (id: string) => {
      try {
        await deleteWorkflow(id);
        setStatus(`已删除：${id}`);
        reloadList();
      } catch (err) {
        Toast.error(`删除失败：${(err as Error).message}`);
      }
    },
    [reloadList],
  );

  return (
    <div
      className="fg-toolbar"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '8px 12px',
        background: '#fff',
        borderBottom: '1px solid rgba(6,7,9,0.10)',
        flexWrap: 'wrap',
        flex: '0 0 auto',
        zIndex: 200,
      }}
    >
      <Typography.Text strong>规则链：</Typography.Text>
      <Input
        value={name}
        onChange={setName}
        style={{ width: 160 }}
        placeholder="链名称"
      />
      <Select
        style={{ width: 200 }}
        placeholder="选择已存在的链"
        value={chainId || undefined}
        onChange={(v) => handleLoad(v as string)}
        optionList={options.map((o) => ({
          label: `${o.name || o.id}（${o.nodeCount} 节点）`,
          value: o.id,
        }))}
        showClear
      />
      <Space>
        <Button size="small" onClick={handleNew}>新建</Button>
        <Button size="small" theme="solid" type="primary" onClick={handleSave}>保存</Button>
        <Button
          size="small"
          type="danger"
          disabled={!chainId}
          onClick={() => chainId && handleDelete(chainId)}
        >
          删除
        </Button>
      </Space>
      {status && (
        <Typography.Text type="tertiary" style={{ marginLeft: 8, fontSize: 12 }}>
          {status}
        </Typography.Text>
      )}
    </div>
  );
};
