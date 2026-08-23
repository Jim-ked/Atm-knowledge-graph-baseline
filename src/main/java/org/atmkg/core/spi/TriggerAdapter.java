package org.atmkg.core.spi;

import java.util.function.Consumer;
import org.atmkg.core.model.ChangeEvent;

public interface TriggerAdapter {
    void start(Consumer<ChangeEvent> consumer);
    void stop();
}
