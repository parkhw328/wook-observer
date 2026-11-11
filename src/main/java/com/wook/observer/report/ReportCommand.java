package com.wook.observer.report;

import com.wook.observer.collector.ObservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "observer.report-dir")
public class ReportCommand implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ReportCommand.class);
    private final ObservationService observations;
    private final ReportWriter writer;
    private final ConfigurableApplicationContext context;
    private final String destination;

    public ReportCommand(ObservationService observations, ReportWriter writer, ConfigurableApplicationContext context,
                         @Value("${observer.report-dir}") String destination) {
        this.observations = observations;
        this.writer = writer;
        this.context = context;
        this.destination = destination;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (destination.isBlank()) { throw new IllegalArgumentException("observer.report-dir must not be blank"); }
        Path path = writer.writeDirectory(observations.collect(), Path.of(destination));
        LOG.info("Report written to {}", path);
        context.close();
    }
}
