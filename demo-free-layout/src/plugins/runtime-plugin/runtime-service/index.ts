/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import {
  IReport,
  NodeReport,
  WorkflowInputs,
  WorkflowOutputs,
  WorkflowStatus,
} from '@flowgram.ai/runtime-interface';
import {
  injectable,
  inject,
  WorkflowDocument,
  Playground,
  WorkflowLineEntity,
  WorkflowNodeEntity,
  Emitter,
} from '@flowgram.ai/free-layout-editor';

import { WorkflowRuntimeClient } from '../client';
import { GetGlobalVariableSchema } from '../../variable-panel-plugin';
import { WorkflowNodeType } from '../../../nodes';
import { normalizeWorkflowForBackend } from '../../../utils/workflow-export';

const SYNC_TASK_REPORT_INTERVAL = 500;

interface NodeRunningStatus {
  nodeID: string;
  status: WorkflowStatus;
  nodeResultLength: number;
}

@injectable()
export class WorkflowRuntimeService {
  @inject(Playground) playground: Playground;

  @inject(WorkflowDocument) document: WorkflowDocument;

  @inject(WorkflowRuntimeClient) runtimeClient: WorkflowRuntimeClient;

  @inject(GetGlobalVariableSchema) getGlobalVariableSchema: GetGlobalVariableSchema;

  private executedEdgeKeys = new Set<string>();

  /** 只记录真正走过（源节点已执行）的连线，避免把未选中的分支也描成流动。 */
  private flowingLineUntil = new Map<string, number>();

  private flowingTimers = new Map<string, ReturnType<typeof setTimeout>>();

  private taskID?: string;

  /** 当前画布对应的已保存工作流 ID：官方 Runtime API 校验/执行时必须携带。 */
  private workflowId?: string;

  private syncTaskReportIntervalID?: ReturnType<typeof setInterval>;

  /** SSE 报告流：优先于轮询，客户端断开 / 后端关闭时回收。 */
  private eventSource?: EventSource;

  /** 结果只上报一次：SSE 终帧与收尾事件可能先后到达，避免重复 fire。 */
  private finished = false;

  private reportEmitter = new Emitter<NodeReport>();

  private resetEmitter = new Emitter<{}>();

  private resultEmitter = new Emitter<{
    errors?: string[];
    result?: {
      inputs: WorkflowInputs;
      outputs: WorkflowOutputs;
    };
  }>();

  private nodeRunningStatus: Map<string, NodeRunningStatus>;

  public onNodeReportChange = this.reportEmitter.event;

  public onReset = this.resetEmitter.event;

  public onResultChanged = this.resultEmitter.event;

  public isFlowingLine(line: WorkflowLineEntity) {
    const from = line.from?.id;
    const to = line.to?.id;
    if (!from || !to) {
      return false;
    }
    return (
      this.executedEdgeKeys.has(`${from}->${to}`) &&
      (this.flowingLineUntil.get(line.id) ?? 0) > Date.now()
    );
  }

  /** 只给后端确认执行过的边着色；条件未命中的分支不会流动。 */
  private markIncomingLines(node: WorkflowNodeEntity, durationMs: number) {
    node.lines.inputLines.forEach((line) => {
      const from = line.from?.id;
      const to = line.to?.id;
      if (from && to && this.executedEdgeKeys.has(`${from}->${to}`)) {
        this.markFlowingLine(line, durationMs);
      }
    });
  }

  /** 给单条线一个最小流动窗口，避免节点执行过快导致动画一闪而过。 */
  private markFlowingLine(line: WorkflowLineEntity, durationMs: number) {
    const until = Date.now() + durationMs;
    const current = this.flowingLineUntil.get(line.id) ?? 0;
    if (until > current) {
      this.flowingLineUntil.set(line.id, until);
    }
    const timer = this.flowingTimers.get(line.id);
    if (timer) {
      clearTimeout(timer);
    }
    this.flowingTimers.set(
      line.id,
      setTimeout(() => {
        this.flowingLineUntil.delete(line.id);
        this.flowingTimers.delete(line.id);
        if (!this.document.disposed) {
          this.document.linesManager.forceUpdate();
        }
      }, Math.max(0, until - Date.now())),
    );
    if (!this.document.disposed) {
      this.document.linesManager.forceUpdate();
    }
  }

  private clearFlowing() {
    this.flowingTimers.forEach((timer) => clearTimeout(timer));
    this.flowingTimers.clear();
    this.flowingLineUntil.clear();
    this.executedEdgeKeys.clear();
    if (!this.document.disposed) {
      this.document.linesManager.forceUpdate();
    }
  }

  /** 由工具栏同步当前工作流 ID，供官方 Runtime API 携带并触发后端实时保存。 */
  public setWorkflowId(workflowId?: string) {
    this.workflowId = workflowId;
  }

  public async taskRun(inputs: WorkflowInputs): Promise<string | undefined> {
    if (this.taskID) {
      await this.taskCancel();
    }
    const isFormValid = await this.validateForm();
    if (!isFormValid) {
      this.resultEmitter.fire({
        errors: ['表单校验失败'],
      });
      return;
    }
    const schema = {
      ...normalizeWorkflowForBackend(this.document.toJSON() as unknown as Record<string, unknown>),
      globalVariable: this.getGlobalVariableSchema(),
    } as Record<string, unknown>;
    const workflowId = this.workflowId ?? (schema as { id?: string }).id;
    const payload = {
      schema: JSON.stringify(schema),
      inputs,
      workflowId,
    };

    const validateResult = await this.runtimeClient.TaskValidate(payload as any);
    if (!validateResult?.valid) {
      this.resultEmitter.fire({
        errors: validateResult?.errors ?? ['服务器内部错误'],
      });
      return;
    }
    this.reset();
    let taskID: string | undefined;
    try {
      const output = await this.runtimeClient.TaskRun(payload as any);
      taskID = output?.taskID;
    } catch (e) {
      this.resultEmitter.fire({
        errors: [(e as Error)?.message],
      });
      return;
    }
    if (!taskID) {
      this.resultEmitter.fire({
        errors: ['任务运行失败'],
      });
      return;
    }
    this.taskID = taskID;
    // 优先使用 SSE 实时推送；客户端不提供流地址（如 browser 模式）时退回轮询
    const streamUrl = this.runtimeClient.getReportStreamUrl?.(taskID);
    if (streamUrl) {
      this.startStream(streamUrl);
    } else {
      this.startPolling();
    }
    return this.taskID;
  }

  public async taskCancel(): Promise<void> {
    if (!this.taskID) {
      return;
    }
    await this.runtimeClient.TaskCancel({
      taskID: this.taskID,
    });
  }

  private async validateForm(): Promise<boolean> {
    const allForms = this.document.getAllNodes().map((node) => node.form);
    const formValidations = await Promise.all(allForms.map(async (form) => form?.validate()));
    const validations = formValidations.filter((validation) => validation !== undefined);
    const isValid = validations.every((validation) => validation);
    return isValid;
  }

  private reset(): void {
    this.taskID = undefined;
    this.finished = false;
    this.nodeRunningStatus = new Map();
    this.clearFlowing();
    if (this.syncTaskReportIntervalID) {
      clearInterval(this.syncTaskReportIntervalID);
      this.syncTaskReportIntervalID = undefined;
    }
    this.closeStream();
    this.resetEmitter.fire({});
  }

  /** 轮询兜底：无 SSE 地址（browser 模式）或 SSE 连接中断时按固定间隔拉取报告。 */
  private startPolling(): void {
    if (this.syncTaskReportIntervalID) {
      return;
    }
    this.syncTaskReportIntervalID = setInterval(() => {
      this.syncTaskReport();
    }, SYNC_TASK_REPORT_INTERVAL);
  }

  private stopPolling(): void {
    if (this.syncTaskReportIntervalID) {
      clearInterval(this.syncTaskReportIntervalID);
      this.syncTaskReportIntervalID = undefined;
    }
  }

  /** 建立 SSE 报告流：report 帧逐节点更新，end 帧或终态后关闭，连接异常退回轮询。 */
  private startStream(url: string): void {
    const eventSource = new EventSource(url);
    this.eventSource = eventSource;
    eventSource.addEventListener('report', (event: MessageEvent) => {
      let report: IReport | undefined;
      try {
        const parsed = JSON.parse(event.data);
        // 与 HTTP 客户端一致：含顶层 code 视为错误帧，交由 applyReport 走失败回收
        report = parsed && parsed.code === undefined ? (parsed as IReport) : undefined;
      } catch {
        report = undefined;
      }
      this.applyReport(report, () => this.closeStream());
    });
    eventSource.addEventListener('end', () => this.closeStream());
    eventSource.onerror = () => {
      this.closeStream();
      // 未正常收尾（如网络抖动）：退回轮询保证最终一致
      if (this.taskID && !this.finished) {
        this.startPolling();
      }
    };
  }

  private closeStream(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = undefined;
    }
  }

  private async syncTaskReport(): Promise<void> {
    if (!this.taskID) {
      return;
    }
    const report = await this.runtimeClient.TaskReport({
      taskID: this.taskID,
    });
    this.applyReport(report, () => this.stopPolling());
  }

  /**
   * 统一处理一份报告（轮询与 SSE 共用）：终止时上报结果 / 错误并回收通道，
   * 随后把逐节点状态交给 {@link updateReport} 点亮画布。
   *
   * @param terminate 回收当前通道（清轮询定时器或关 SSE），仅首次终止时调用
   */
  private applyReport(report: IReport | undefined, terminate: () => void): void {
    if (!report) {
      terminate();
      console.error('Sync task report failed');
      return;
    }
    const { workflowStatus, inputs, outputs, messages } = report;
    if (workflowStatus.terminated && !this.finished) {
      this.finished = true;
      terminate();
      if (Object.keys(outputs).length > 0) {
        this.resultEmitter.fire({ result: { inputs, outputs } });
      } else {
        this.resultEmitter.fire({
          errors: messages?.error?.map((message) =>
            message.nodeID ? `${message.nodeID}: ${message.message}` : message.message
          ),
        });
      }
    }
    this.updateReport(report);
  }

  private updateReport(report: IReport): void {
    const { reports } = report;
    const nodes = this.document.getAllNodes().filter(
      (node) =>
        ![WorkflowNodeType.BlockStart, WorkflowNodeType.BlockEnd].includes(
          node.flowNodeType as WorkflowNodeType
        )
    );

    // 后端返回真实执行边（source->target），只描摹这些边。
    const executedEdges = (report as { executedEdges?: string[] }).executedEdges;
    if (executedEdges) {
      executedEdges.forEach((edge) => this.executedEdgeKeys.add(edge));
    }

    nodes.forEach((node) => {
      const nodeID = node.id;
      const nodeReport = reports[nodeID];
      // 即使节点报告尚未到达，只要边已执行也可以先点亮路线
      this.markIncomingLines(
        node,
        nodeReport?.status === WorkflowStatus.Processing ? 900 : 350,
      );
      if (!nodeReport) {
        return;
      }
      const runningStatus = this.nodeRunningStatus.get(nodeID);
      if (
        !runningStatus ||
        nodeReport.status !== runningStatus.status ||
        nodeReport.snapshots.length !== runningStatus.nodeResultLength
      ) {
        this.nodeRunningStatus.set(nodeID, {
          nodeID,
          status: nodeReport.status,
          nodeResultLength: nodeReport.snapshots.length,
        });
        this.reportEmitter.fire(nodeReport);
        this.document.linesManager.forceUpdate();
      } else if (nodeReport.status === WorkflowStatus.Processing) {
        this.reportEmitter.fire(nodeReport);
      }
    });
  }
}
