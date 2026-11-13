/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

declare module '*.svg'
declare module '*.png'
declare module '*.jpg'
declare module '*.module.less'

/** 构建期注入的功能开关，见 rsbuild.config.ts 的 source.define。 */
declare const __FLOWGRAM_ENABLE_NODE_CONFIG_PREVIEW__: boolean;
