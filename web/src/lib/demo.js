/**
 * 内置示例链路：后端 /designer/example 不可用（例如只跑 Vite dev）时的兜底文档。
 * 结构与 fire-alarm-chain.json 一致，方便随时对照。
 */
const DEMO_CHAIN = {
  id: 'fireAlarm',
  name: '消防隐患智能处置链',
  nodes: {
    recv: { type: 'log', props: { prefix: '[INPUT]' } },
    validate: { type: 'deviceValidate' },
    riskFilter: {
      type: 'filter',
      props: {
        expr: 'telemetry.temperature > threshold.temperature || telemetry.smokeDensity > threshold.smokeDensity'
      }
    },
    enrich: { type: 'riskEnrich', props: { criticalScore: '80', warnScore: '50' } },
    aiJudge: { type: 'aiDecision', props: { agent: 'riskJudge', latencyMs: '200' } },
    notify: { type: 'notify' },
    persist: { type: 'asyncSink', props: { target: 'tdengine' } },
    archive: { type: 'archive' }
  },
  edges: {
    recv: [{ to: 'validate' }],
    validate: [
      { to: 'riskFilter', relation: 'Valid' },
      { to: 'archive', relation: 'Invalid' }
    ],
    riskFilter: [
      { to: 'enrich', relation: 'True' },
      { to: 'archive', relation: 'False' }
    ],
    enrich: [{ to: 'aiJudge' }],
    aiJudge: [
      { to: 'notify', relation: '立即处置' },
      { to: 'persist', relation: '持续观察' },
      { to: 'archive', relation: '无需处置' }
    ],
    notify: [{ to: 'persist' }]
  },
  entry: ['recv']
}

export const DEMO_CHAIN_TEXT = JSON.stringify(DEMO_CHAIN, null, 2)
