/**
 * 左侧节点面板：按分类分组，支持拖拽到画布添加节点。
 *
 * - 数据来源：后端 `/flowgram/nodes` 目录（经 build-registries 缓存），用 getPaletteGroups() 分组；
 * - 拖拽：HTML5 draggable，dragStart 将节点 type 写入 dataTransfer；
 *   画布容器在 onDrop 中读取 type 并按落点坐标创建节点（见 editor.tsx）。
 *
 * 该组件渲染在 FreeLayoutEditorProvider 内部（当前仅展示目录，无需 ctx；
 * 若后续需要点击添加，可用 useClientContext）。
 */

import { memo, useMemo, useState } from 'react';

import type { FlowgramNodeDefinition } from '../../api/flowgram';
import { getPaletteGroups } from '../../nodes/build-registries';

/** dataTransfer 自定义类型 key（与 editor.tsx onDrop 保持一致） */
export const FLOWGRAM_NODE_MIME = 'application/x-flowgram-node';

/** 分类色（按分类名哈希取色，保证同类同色） */
const PALETTE_COLORS = [
  '#646aef',
  '#12b886',
  '#f59f00',
  '#e8590c',
  '#1c7ed6',
  '#d6336c',
  '#7048e8',
  '#0ca678',
];

function categoryColor(category: string): string {
  let hash = 0;
  for (let i = 0; i < category.length; i += 1) {
    hash = (hash * 31 + category.charCodeAt(i)) >>> 0;
  }
  return PALETTE_COLORS[hash % PALETTE_COLORS.length];
}

interface PaletteItemProps {
  node: FlowgramNodeDefinition;
}

const PaletteItem = memo(({ node }: PaletteItemProps) => (
  <div
    className="fg-palette__item"
    draggable
    title={node.description || node.title || node.type}
    onDragStart={(e) => {
      e.dataTransfer.setData(FLOWGRAM_NODE_MIME, node.type);
      e.dataTransfer.effectAllowed = 'copy';
    }}
  >
    <span className="fg-palette__dot" style={{ background: categoryColor(node.category || 'other') }} />
    <span className="fg-palette__name">{node.title || node.type}</span>
    <span className="fg-palette__type">{node.type}</span>
  </div>
));
PaletteItem.displayName = 'PaletteItem';

export const LeftPalette = memo(() => {
  const groups = useMemo(() => getPaletteGroups(), []);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const toggle = (category: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  return (
    <div className="fg-palette">
      <div className="fg-palette__header">节点库</div>
      <div className="fg-palette__body">
        {groups.length === 0 && (
          <div className="fg-palette__empty">暂无节点目录（后端 /flowgram/nodes 未就绪）</div>
        )}
        {groups.map((group) => {
          const isCollapsed = collapsed.has(group.category);
          return (
            <div className="fg-palette__group" key={group.category}>
              <div className="fg-palette__group-title" onClick={() => toggle(group.category)}>
                <span
                  className="fg-palette__caret"
                  style={{ transform: isCollapsed ? 'rotate(-90deg)' : 'rotate(0deg)' }}
                >
                  ▾
                </span>
                <span className="fg-palette__group-name">{group.category}</span>
                <span className="fg-palette__group-count">{group.nodes.length}</span>
              </div>
              {!isCollapsed && (
                <div className="fg-palette__items">
                  {group.nodes.map((node) => (
                    <PaletteItem node={node} key={node.type} />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
});
LeftPalette.displayName = 'LeftPalette';
