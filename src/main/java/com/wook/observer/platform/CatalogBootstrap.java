package com.wook.observer.platform;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("!collector")
public class CatalogBootstrap implements ApplicationRunner {
    private final Catalog catalog;
    public CatalogBootstrap(Catalog catalog) { this.catalog = catalog; }
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (((Number) catalog.status().get("revision")).intValue() == 0) {
            try (var input = new ClassPathResource("catalog/bootstrap.json").getInputStream()) {
                catalog.importBytes(input.readAllBytes(), "bundled-starter-2026-11-11 (5 records; not a complete vulnerability feed)");
            }
        }
    }
}
