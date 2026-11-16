package com.wook.observer.platform;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!collector")
public class OperationLifecycle {
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        OperationLog.info("application.ready", "version", "1.8.0", "java", Runtime.version(),
                "maxHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024), "scanWorkers", 2, "scanQueueCapacity", 16);
    }
    @EventListener(ContextClosedEvent.class)
    public void stopping() { OperationLog.info("application.stopping"); }
}
