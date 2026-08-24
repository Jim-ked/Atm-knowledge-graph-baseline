package org.atmkg.core.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 一条 SourceRef 图投影在删除前的 UID 摘要。
 *
 * <p>{@code entityUids} 表示该 SourceRef 原来贡献过的实体，不表示这些 canonical 实体一定被物理删除；
 * 其他来源仍有 contribution 时实体会继续存在。{@code relationshipUids} 只包含该 SourceRef 直接拥有的关系，
 * {@code anchorEntityUids} 则包含受影响实体以及这些关系的两端。这里不保存属性、业务数据或图数据库内部 ID。
 */
public final class GraphProjectionSnapshot {
    private static final GraphProjectionSnapshot EMPTY =
            new GraphProjectionSnapshot(List.of(), List.of(), List.of());

    private final List<String> entityUids;
    private final List<String> relationshipUids;
    private final List<String> anchorEntityUids;

    public GraphProjectionSnapshot(List<String> entityUids, List<String> relationshipUids,
                                   List<String> anchorEntityUids) {
        this.entityUids = distinct(entityUids, "entityUids");
        this.relationshipUids = distinct(relationshipUids, "relationshipUids");
        this.anchorEntityUids = distinct(anchorEntityUids, "anchorEntityUids");
    }

    public static GraphProjectionSnapshot empty() {
        return EMPTY;
    }

    private static List<String> distinct(List<String> values, String name) {
        return List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(values, name)));
    }

    public List<String> getEntityUids() { return entityUids; }
    public List<String> getRelationshipUids() { return relationshipUids; }
    public List<String> getAnchorEntityUids() { return anchorEntityUids; }
}
