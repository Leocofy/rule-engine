package io.fire.rule.runtime;

/**
 * Description: TaskExcuteException
 *
 * @author Cai FeiYue
 * @date 2026/10/16
 */
public class TaskExecuteException extends RuntimeException {

    public TaskExecuteException(String message) {
        super(message);
    }

    public TaskExecuteException(String message, Throwable cause) {
        super(message, cause);
    }

}
