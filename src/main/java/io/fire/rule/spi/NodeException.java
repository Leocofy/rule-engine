package io.fire.rule.spi;

/** 节点初始化或执行异常。fatal=true 表示配置错误，不应重试。 */
public class NodeException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean fatal;

    public NodeException(String message) {
        this(message, false);
    }

    public NodeException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public NodeException(String message, Throwable cause) {
        super(message, cause);
        this.fatal = false;
    }

    /** 配置类错误不重试，否则会无限失败。 */
    public boolean isFatal() {
        return fatal;
    }
}
