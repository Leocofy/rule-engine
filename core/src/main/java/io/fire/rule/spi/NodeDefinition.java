package io.fire.rule.spi;

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

    public NodeDefinition(NodeComponent def) {
        this.type = def.type();
        this.title = def.title();
        this.description = def.description();
        this.category = def.category();
        this.version = def.version();
        this.relations = def.relations();
        this.customRelations = def.customRelations();
        this.relations = def.relations();
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

}
