package com.wook.observer.collector;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One maintained inventory for both SSH and disconnected hosts. */
public final class CollectionScripts {
    private CollectionScripts() { }

    public static String inventory() throws IOException {
        return resource("ssh-inventory.sh")
                .replace("# SYSTEM_INVENTORY", SystemInventoryCollector.script())
                .replace("# LIBRARY_INVENTORY", resource("ssh-libraries.py"));
    }

    public static String offline() throws IOException {
        return resource("offline-collect.sh").replace("# OBSERVER_INVENTORY", inventory());
    }

    private static String resource(String name) throws IOException {
        try (var input = new ClassPathResource("collector/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
