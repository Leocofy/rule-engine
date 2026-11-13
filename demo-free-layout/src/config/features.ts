/**
 * 前端功能开关的单一来源。
 *
 * <p>开关注入由 rsbuild.config.ts 的 source.define 完成，构建期会替换为布尔常量；
 * 组件只读这里的 featureFlags，不再各自判断 NODE_ENV。
 */

export const featureFlags = {
  /**
   * 节点配置实时预览：开发环境默认开启，生产构建默认关闭。
   */
  nodeConfigPreview: __FLOWGRAM_ENABLE_NODE_CONFIG_PREVIEW__,
} as const;