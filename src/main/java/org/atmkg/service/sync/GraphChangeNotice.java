package org.atmkg.service.sync;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;

/**
 * 新增源字段/表/mapping 不改本类。只有“成功写图后通知”的应用契约变化才进入这里；源侧发现信息仍放
 * ChangeEvent。entityUids/relationshipUids/anchorEntityUids 必须直接来自已提交 MappingResult。
 *
 * <p>从 QueryService/GraphStore 再猜 anchor 会让通知与本次提交不一致；GraphStore 失败前发布会产生假成功。
 * notice 缺失先查 DefaultSyncService listener 是否在 replaceProjection 后调用。DELETE 当前无 before-state，
 * entity/relationship/anchor 均为空，不能在这里虚构历史 UID。
 */
public final class GraphChangeNotice {
    public enum Operation { UPSERT, DELETE }

    private final SourceRef sourceRef;
    private final Operation operation;
    private final List<String> entityUids;
    private final List<String> relationshipUids;
    private final List<String> anchorEntityUids;
    private final Instant occurredAt;

    public GraphChangeNotice(SourceRef sourceRef, Operation operation, List<String> entityUids,
                             List<String> relationshipUids, Instant occurredAt) {
        this(sourceRef, operation, entityUids, relationshipUids,
                operation == Operation.UPSERT ? distinct(entityUids) : List.of(), occurredAt);
    }

    private GraphChangeNotice(SourceRef sourceRef, Operation operation, List<String> entityUids,
                              List<String> relationshipUids, List<String> anchorEntityUids, Instant occurredAt) {
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.entityUids = List.copyOf(Objects.requireNonNull(entityUids, "entityUids"));
        this.relationshipUids = List.copyOf(Objects.requireNonNull(relationshipUids, "relationshipUids"));
        this.anchorEntityUids = List.copyOf(Objects.requireNonNull(anchorEntityUids, "anchorEntityUids"));
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** 只根据已经提交到 GraphStore 的 MappingResult 构造 UPSERT notice。 */
    public static GraphChangeNotice forUpsert(SourceRef sourceRef, MappingResult result, Instant occurredAt) {
        Objects.requireNonNull(result, "result");
        List<String> entityUids = result.getEntities().stream().map(entity -> entity.getUid()).toList();
        List<String> relationshipUids = result.getRelationships().stream()
                .map(relationship -> relationship.getUid()).toList();
        LinkedHashSet<String> anchors = new LinkedHashSet<>(entityUids);
        result.getRelationships().forEach(relationship -> {
            anchors.add(relationship.getSourceUid());
            anchors.add(relationship.getTargetUid());
        });
        return new GraphChangeNotice(sourceRef, Operation.UPSERT, entityUids, relationshipUids,
                List.copyOf(anchors), occurredAt);
    }

    public static GraphChangeNotice forDelete(SourceRef sourceRef, Instant occurredAt) {
        return new GraphChangeNotice(sourceRef, Operation.DELETE, List.of(), List.of(), List.of(), occurredAt);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(values, "values")));
    }

    public SourceRef getSourceRef() { return sourceRef; }
    public Operation getOperation() { return operation; }
    public List<String> getEntityUids() { return entityUids; }
    public List<String> getRelationshipUids() { return relationshipUids; }
    public List<String> getAnchorEntityUids() { return anchorEntityUids; }
    public Instant getOccurredAt() { return occurredAt; }
}
