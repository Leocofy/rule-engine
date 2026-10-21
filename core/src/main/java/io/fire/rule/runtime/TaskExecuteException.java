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

    public TaskExecuteException(Class<?> clazz, String msg) {
        super(clazz.getSimpleName() + ": " + msg);
    }

    public TaskExecuteException(Class<?> clazz, String msg, Throwable cause) {
        super(clazz.getSimpleName() + ": " + msg, cause);
    }

    public TaskExecuteException(String message, Throwable cause) {
        super(message, cause);
    }

}
