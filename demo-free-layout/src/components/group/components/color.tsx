/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { FC } from 'react';

import { Field } from '@flowgram.ai/free-layout-editor';
import { Popover, Tooltip } from '@douyinfe/semi-ui';

import { GroupField } from '../constant';
import { defaultColor, groupColors } from '../color';

/** 颜色名展示文案（存储值仍为英文 key，避免影响既有数据） */
const COLOR_LABELS: Record<string, string> = {
  Red: '红色',
  Orange: '橙色',
  Amber: '琥珀色',
  Yellow: '黄色',
  Lime: '青柠色',
  Green: '绿色',
  Emerald: '翡翠绿',
  Teal: '蓝绿色',
  Cyan: '青色',
  Sky: '天蓝色',
  Blue: '蓝色',
  Indigo: '靛蓝色',
  Violet: '紫罗兰色',
  Purple: '紫色',
  Fuchsia: '洋红色',
  Pink: '粉色',
  Rose: '玫瑰色',
  Gray: '灰色',
};

export const GroupColor: FC = () => (
  <Field<string> name={GroupField.Color}>
    {({ field }) => {
      const colorName = field.value ?? defaultColor;
      return (
        <Popover
          position="top"
          mouseLeaveDelay={300}
          content={
            <div className="workflow-group-color-palette">
              {Object.entries(groupColors).map(([name, color]) => (
                <Tooltip content={COLOR_LABELS[name] ?? name} key={name} mouseEnterDelay={300}>
                  <span
                    className="workflow-group-color-item"
                    key={name}
                    style={{
                      backgroundColor: color['300'],
                      borderColor: name === colorName ? color['400'] : '#fff',
                    }}
                    onClick={() => field.onChange(name)}
                  />
                </Tooltip>
              ))}
            </div>
          }
        >
          <span
            className="workflow-group-color"
            style={{
              backgroundColor: groupColors[colorName]['300'],
            }}
          />
        </Popover>
      );
    }}
  </Field>
);
