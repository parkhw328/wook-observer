package com.wook.observer.platform;

import com.wook.observer.model.SystemInventory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pinned official repository-to-CPE mapping; never contacts Red Hat at runtime. */
final class RedHatRepositories {
    private static final JsonNode DATA = load();

    private static JsonNode load() {
        try (var input = RedHatRepositories.class.getResourceAsStream("/catalog/redhat-repositories.json")) {
            return JsonMapper.builder().build().readTree(input).path("repositories");
        } catch (Exception exception) { throw new IllegalStateException("RHEL repository mapping unavailable", exception); }
    }

    static Set<String> ecosystems(SystemInventory.RpmInventory inventory, String osVersion) {
        if (inventory.repositoryStatus() != SystemInventory.Status.COLLECTED) { return Set.of(); }
        Set<String> result = new LinkedHashSet<>();
        String release = inventory.releaseVersion().isEmpty() ? osVersion : inventory.releaseVersion();
        for (String repository : inventory.enabledRepositories()) {
            JsonNode mapped = DATA.path(repository);
            if (repository.matches(".*-(eus|e4s|aus|tus)-.*")) {
                // The release lock is independent of VERSION_ID; never guess an EUS minor release.
                if (!inventory.releaseVersion().matches("[0-9]+\\.[0-9]+")) { continue; }
                mapped = DATA.path(repository + "__" + inventory.releaseVersion().replace(".", "_DOT_"));
            }
            for (JsonNode value : mapped) {
                String ecosystem = value.asText();
                String[] fields = ecosystem.split(":", -1);
                if (fields.length < 3) { continue; }
                String version = fields[2];
                if (version.equals(release) || (!version.contains(".") && version.equals(release.split("\\.")[0]))) {
                    result.add(ecosystem);
                }
            }
        }
        return Set.copyOf(result);
    }

    private RedHatRepositories() { }
}
