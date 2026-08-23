package org.atmkg.service.change;

import java.util.List;
import org.atmkg.core.model.GraphRelationship;

/**
 * 当前不要实现、实例化或修改。RouteSegment-Airspace 空间关系缺少几何候选筛选、拓扑/高度判断及关系
 * ownership 设计，不能靠实现 {@link #derive(String)} 就宣称完成。
 *
 * <p>未来只有派生计算输入/输出契约经设计确认时才改本接口。它目前没有 JTS/GeoTools、GraphChangeNotice
 * 自动调用、Neo4j persistence、reconcile 或 ownership；在这些边界前接入运行时会产生无法正确删除/更新的关系。
 */
@FunctionalInterface
public interface DerivedRelationPlugin {
    List<GraphRelationship> derive(String changedEntityUid);
}
