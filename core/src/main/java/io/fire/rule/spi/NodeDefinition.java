package io.fire.rule.spi;

import java.util.Arrays;

/**
 * Description: NodeDefinition
 *
 * @author Cai FeiYue
 * @date 2026/10/20
 */
public class NodeDefinition {

    private String type;

    private String title;

    private String description;

    private String category;

    private String version;

    private String[] relations;

    private boolean customRelations;

    public NodeDefinition(Builder builder) {
        this.type = builder.type;
        this.title = builder.title;
        this.description = builder.description;
        this.category = builder.category;
        this.version = builder.version;
        this.relations = builder.relations;
        this.customRelations = builder.customRelations;
    }

    public NodeDefinition(RuleNodeDefinition definition) {
        this.type = definition.type();
        this.title = definition.title();
        this.description = definition.description();
        this.category = definition.category();
        this.version = definition.version();
        this.relations = definition.relations();
        this.customRelations = definition.customRelations();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String[] getRelations() {
        return relations;
    }

    public void setRelations(String[] relations) {
        this.relations = relations;
    }

    public boolean isCustomRelations() {
        return customRelations;
    }

    public void setCustomRelations(boolean customRelations) {
        this.customRelations = customRelations;
    }

    @Override
    public String toString() {
        return "NodeDefinition{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", category='" + category + '\'' +
                ", version='" + version + '\'' +
                ", relations=" + Arrays.toString(relations) +
                ", customRelations=" + customRelations +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String title;
        private String description;
        private String category;
        private String version;
        private String[] relations;
        private boolean customRelations;

        public NodeDefinition build() {
            return new NodeDefinition(this);
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder relations(String[] relations) {
            this.relations = relations;
            return this;
        }

        public Builder customRelations(boolean customRelations) {
            this.customRelations = customRelations;
            return this;
        }
    }

}
