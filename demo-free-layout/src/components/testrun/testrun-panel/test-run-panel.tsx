/**
 * Copyright (c) 2025 Bytedance Ltd. and/or its affiliates
 * SPDX-License-Identifier: MIT
 */

import { FC, useState, useEffect } from 'react';

import classnames from 'classnames';
import { WorkflowInputs, WorkflowOutputs, NodeReport } from '@flowgram.ai/runtime-interface';
import { useService } from '@flowgram.ai/free-layout-editor';
import { Button, Switch } from '@douyinfe/semi-ui';
import { IconClose, IconPlay, IconSpin } from '@douyinfe/semi-icons';

import { TestRunJsonInput } from '../testrun-json-input';
import { TestRunForm } from '../testrun-form';
import { NodeStatusGroup } from '../node-status-bar/group';
import { WorkflowRuntimeService } from '../../../plugins/runtime-plugin/runtime-service';
import { useTestRunFormPanel } from '../../../plugins/panel-manager-plugin/hooks';
import { IconCancel } from '../../../assets/icon-cancel';

import styles from './index.module.less';

export interface TestRunSidePanelProps {}

/** 运行状态英文枚举 → 中文 */
const STATUS_TEXT: Record<string, string> = {
  pending: '等待中',
  processing: '运行中',
  succeeded: '成功',
  failed: '失败',
  cancelled: '已取消',
};

const formatStatus = (status?: string): string =>
  status ? STATUS_TEXT[status.toLowerCase()] ?? status : '';

export const TestRunSidePanel: FC<TestRunSidePanelProps> = () => {
  const runtimeService = useService(WorkflowRuntimeService);
  const { close: closePanel } = useTestRunFormPanel();
  const [isRunning, setRunning] = useState(false);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [errors, setErrors] = useState<string[]>();
  const [result, setResult] = useState<
    | {
        inputs: WorkflowInputs;
        outputs: WorkflowOutputs;
      }
    | undefined
  >();
  /** 运行中逐节点实时报告：每完成一个节点就追加其输入/输出，形成流式明细。 */
  const [liveReports, setLiveReports] = useState<Record<string, NodeReport>>({});

  // en - Use localStorage to persist the JSON mode state
  const [inputJSONMode, _setInputJSONMode] = useState(() => {
    const savedMode = localStorage.getItem('testrun-input-json-mode');
    return savedMode ? JSON.parse(savedMode) : false;
  });

  const setInputJSONMode = (checked: boolean) => {
    _setInputJSONMode(checked);
    localStorage.setItem('testrun-input-json-mode', JSON.stringify(checked));
  };

  const onTestRun = async () => {
    if (isRunning) {
      await runtimeService.taskCancel();
      return;
    }
    setResult(undefined);
    setErrors(undefined);
    setLiveReports({});
    const taskID = await runtimeService.taskRun(values);
    if (taskID) {
      setRunning(true);
    }
  };

  const onClose = async () => {
    await runtimeService.taskCancel();
    setValues({});
    setLiveReports({});
    setRunning(false);
    closePanel();
  };

  const renderLiveReports = () => {
    const reports = Object.values(liveReports);
    if (reports.length === 0) {
      return null;
    }
    return (
      <div
        style={{
          width: '100%',
          maxHeight: 320,
          overflow: 'auto',
          marginTop: 12,
          textAlign: 'left',
        }}
      >
        {reports.map((report) => {
          const snapshot = report.snapshots?.[report.snapshots.length - 1];
          return (
            <div key={report.id} style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, fontWeight: 600 }}>
                {report.id} · {formatStatus(report.status)}
              </div>
              <NodeStatusGroup title="输入" data={snapshot?.inputs} optional />
              <NodeStatusGroup title="输出" data={snapshot?.outputs} optional />
            </div>
          );
        })}
      </div>
    );
  };

  const renderRunning = (
    <div className={styles['testrun-panel-running']}>
      <IconSpin spin size="large" />
      <div className={styles.text}>运行中...</div>
      {renderLiveReports()}
    </div>
  );

  const renderForm = (
    <div className={styles['testrun-panel-form']}>
      <div className={styles['testrun-panel-input']}>
        <div className={styles.title}>输入表单</div>
        <div>JSON 模式</div>
        <Switch
          checked={inputJSONMode}
          onChange={(checked: boolean) => setInputJSONMode(checked)}
          size="small"
        />
      </div>
      {inputJSONMode ? (
        <TestRunJsonInput values={values} setValues={setValues} />
      ) : (
        <TestRunForm values={values} setValues={setValues} />
      )}
      {errors?.map((e) => (
        <div className={styles.error} key={e}>
          {e}
        </div>
      ))}
      {renderLiveReports()}
      <NodeStatusGroup title="输入结果" data={result?.inputs} optional disableCollapse />
      <NodeStatusGroup title="输出结果" data={result?.outputs} optional disableCollapse />
    </div>
  );

  const renderButton = (
    <Button
      onClick={onTestRun}
      icon={isRunning ? <IconCancel /> : <IconPlay size="small" />}
      className={classnames(styles.button, {
        [styles.running]: isRunning,
        [styles.default]: !isRunning,
      })}
    >
      {isRunning ? '取消' : '测试运行'}
    </Button>
  );

  useEffect(() => {
    const disposer = runtimeService.onResultChanged(({ result, errors }) => {
      setRunning(false);
      setResult(result);
      if (errors) {
        setErrors(errors);
      } else {
        setErrors(undefined);
      }
    });
    return () => disposer.dispose();
  }, []);

  useEffect(() => {
    const disposer = runtimeService.onNodeReportChange((report) => {
      setLiveReports((prev) => ({ ...prev, [report.id]: report }));
    });
    return () => disposer.dispose();
  }, [runtimeService]);

  useEffect(
    () => () => {
      runtimeService.taskCancel();
    },
    [runtimeService]
  );

  return (
    <div className={styles['testrun-panel-container']}>
      <div className={styles['testrun-panel-header']}>
        <div className={styles['testrun-panel-title']}>测试运行</div>
        <Button
          className={styles['testrun-panel-title']}
          type="tertiary"
          icon={<IconClose />}
          size="small"
          theme="borderless"
          onClick={onClose}
        />
      </div>
      <div className={styles['testrun-panel-content']}>
        {isRunning ? renderRunning : renderForm}
      </div>
      <div className={styles['testrun-panel-footer']}>{renderButton}</div>
    </div>
  );
};
