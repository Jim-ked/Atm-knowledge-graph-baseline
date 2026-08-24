package org.atmkg.core.spi;

import java.util.Collection;
import java.util.Optional;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;

/**
 * 图投影的持久化边界，Neo4j 的事务、标签和索引细节必须留在实现层。
 * 新增业务字段、实体或关系不应修改本接口；只有通用图写入契约变化才进入这里。
 */
public interface GraphStore {
    void initializeSchema();
    void upsertEntities(Collection<GraphEntity> entities);
    void upsertRelationships(Collection<GraphRelationship> relationships);

    /**
     * 原子替换一条权威源记录产生的图投影，旧投影中已经失效的属性和关系不得残留。
     */
    void replaceProjection(SourceRef sourceRef, MappingResult currentProjection);

    /**
     * 原子删除一条源记录对应的图投影，并返回同一删除事务中取得的删除前 UID 摘要。
     * 返回的实体 UID 表示该源记录原来贡献过这些实体，不表示 canonical 实体一定被物理删除。
     */
    GraphProjectionSnapshot deleteProjection(SourceRef sourceRef);

    void deleteEntity(String uid);
    void deleteRelationship(String uid);
    Optional<GraphEntity> findEntity(String uid);

    /** 仅供显式 fullRebuild 使用的项目范围维护操作。 */
    void clearProject();
    GraphStoreStats stats();
}
