package org.atmkg.core.spi;

import java.util.function.Consumer;
import org.atmkg.core.model.ChangeEvent;

/**
 * 只发现源记录身份变化并发送 ChangeEvent，不携带权威业务数据，也不直接写图。
 * 普通 polling scope 和时间水位从正式配置调整，不修改接口。
 */
public interface TriggerAdapter {
    void start(Consumer<ChangeEvent> consumer);
    void stop();
}
