package org.atmkg.service.change;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.atmkg.core.model.SourceRef;
import org.atmkg.service.sync.GraphChangeNotice;

/**
 * 当前正式服务的人工观察出口，只打印一行状态与计数；它不是可靠消息系统、审计日志或业务传输协议。
 */
public final class GraphChangeConsoleReporter implements Consumer<GraphChangeProjectionResult> {
    private final PrintStream output;

    public GraphChangeConsoleReporter(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void accept(GraphChangeProjectionResult result) {
        Objects.requireNonNull(result, "result");
        GraphChangeNotice notice = result.getNotice();
        SourceRef ref = notice.getSourceRef();
        output.printf(Locale.ROOT,
                "[CHANGE] %s source=%s/%s/%s entities=%d relationships=%d anchors=%d " +
                        "neighborhood=%s neighborhoods=%d associations=%d%n",
                notice.getOperation(), oneLine(ref.getSourceId()), oneLine(ref.getObjectName()),
                oneLine(ref.getSourceKey()), notice.getEntityUids().size(), notice.getRelationshipUids().size(),
                notice.getAnchorEntityUids().size(), result.getNeighborhood().getStatus(),
                result.getNeighborhood().getSnapshots().size(), result.getAssociations().size());
    }

    private static String oneLine(String value) {
        return value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
    }
}
