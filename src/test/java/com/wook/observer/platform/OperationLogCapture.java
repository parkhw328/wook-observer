package com.wook.observer.platform;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class OperationLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    final Logger logger = (Logger) LoggerFactory.getLogger("com.wook.observer.operations");
    private final Level previous = logger.getLevel();
    OperationLogCapture(Level level) { logger.setLevel(level); start(); logger.addAppender(this); }
    @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); events.add(event); }
    String text() { return String.join("\n", events.stream().map(ILoggingEvent::getFormattedMessage).toList()); }
    @Override public void close() { logger.detachAppender(this); logger.setLevel(previous); stop(); }
}
