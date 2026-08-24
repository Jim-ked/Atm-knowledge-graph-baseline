package org.atmkg.core.spi;

import java.time.Instant;
import java.util.Collection;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.SourceScope;

/**
 * 协调权威源回读、语义映射和图投影替换；不负责源数据发现细节或查询展示。
 * 新增源入口、字段或关系通常分别修改 sources 配置、正式 TTL 和人工 mapping，不改本接口。
 */
public interface SyncService {
    void handle(ChangeEvent event);

    /**
     * 刷新一个 sourceObject 当前存在的全部记录。实现采用两遍处理：先写实体端点，再写完整投影，
     * 避免同一 sourceObject 内的记录引用依赖源行顺序。本方法不能发现没有 DELETE 事件的消失记录。
     */
    void fullSync(String sourceId, String objectName);

    /**
     * 显式执行项目级重建：先清除项目投影，再跨全部 scope 写实体端点，最后写关系和完整投影。
     * 这是人工危险操作，不应在服务启动时自动调用。
     */
    void fullRebuild(Collection<SourceScope> scopes);

    void compensateSince(String sourceId, String objectName, Instant since);
    void resync(String sourceId, String objectName, String sourceKey);
}
