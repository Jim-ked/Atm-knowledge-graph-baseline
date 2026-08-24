package org.atmkg.service.change;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.atmkg.service.sync.GraphChangeNotice;

/**
 * 把成功写图后的 notice 交给现有 Neighborhood/Association Projector，再把统一结果交给下游 Consumer。
 *
 * <p>本类不读取数据源、不写图、不定义业务消息协议，也不提供 durable delivery。Projector 或下游失败会
 * 原样向上传播，使 polling 等上游保持 at-least-once 重试语义。
 */
public final class GraphChangeProcessor implements Consumer<GraphChangeNotice> {
    private final GraphChangeNeighborhoodProjector neighborhoodProjector;
    private final GraphChangeAssociationProjector associationProjector;
    private final Consumer<GraphChangeProjectionResult> resultConsumer;

    public GraphChangeProcessor(GraphChangeNeighborhoodProjector neighborhoodProjector,
                                GraphChangeAssociationProjector associationProjector,
                                Consumer<GraphChangeProjectionResult> resultConsumer) {
        this.neighborhoodProjector = Objects.requireNonNull(neighborhoodProjector, "neighborhoodProjector");
        this.associationProjector = Objects.requireNonNull(associationProjector, "associationProjector");
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer");
    }

    @Override
    public void accept(GraphChangeNotice notice) {
        Objects.requireNonNull(notice, "notice");
        GraphChangeNeighborhoodResult neighborhood = neighborhoodProjector.project(notice);
        List<AssociationQueryResult> associations = associationProjector.project(notice);
        resultConsumer.accept(new GraphChangeProjectionResult(notice, neighborhood, associations));
    }
}
