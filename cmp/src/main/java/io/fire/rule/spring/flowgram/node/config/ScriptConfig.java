package io.fire.rule.spring.flowgram.node.config;

/**
 * 脚本节点强模型：对应 FlowGram {@code data.script}。
 */
public class ScriptConfig {

    private Script script;

    public Script getScript() {
        return script;
    }

    public void setScript(Script script) {
        this.script = script;
    }

    public static class Script {

        private String language = "javascript";

        private String content;

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}