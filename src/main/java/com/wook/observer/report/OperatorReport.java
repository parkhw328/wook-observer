package com.wook.observer.report;

import com.wook.observer.model.Assessment;
import com.wook.observer.model.Observation;
import com.wook.observer.model.ServiceCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A presentation of saved evidence, never a new vulnerability assessment. */
public record OperatorReport(List<Action> actions, List<CoverageCount> coverage,
                             List<LibraryGroup> libraries, List<IssueGroup> issues) {
    public record Location(String asset, Long pid, String containerId, String path) {
        public String label() { return pid == null ? asset : "PID " + pid + (containerId.isBlank() ? "" : " / " + containerId) + " / " + path; }
    }
    public record Action(String kind, String id, String status, String severity, String serviceGroup,
                         String packageName, String version, String title, List<Location> locations,
                         List<String> evidence, List<String> recommendations, List<String> references, List<String> aliases) { }
    public record CoverageCount(String kind, String status, String reason, int count) { }
    public record LibraryGroup(ServiceCategory category, String name, String version, String status,
                               List<String> advisories, List<String> evidence, List<Location> locations) { }
    public record IssueGroup(String layer, String message, List<String> targets) { }

    public static OperatorReport from(Observation report) {
        Map<Long, Observation.ProcessInfo> processes = new LinkedHashMap<>();
        report.processes().forEach(p -> processes.put(p.pid(), p));
        Map<String, Location> locations = new LinkedHashMap<>();
        Map<String, String> groups = new LinkedHashMap<>();
        Map<String, Observation.Library> libraryByAsset = new LinkedHashMap<>();
        locations.put("host", new Location("host", null, "", ""));
        groups.put("host", "호스트");
        report.containers().forEach(c -> {
            String asset = "container:" + c.id();
            locations.put(asset, new Location(asset, null, c.id(), c.name()));
            groups.put(asset, c.category().label());
        });
        report.processes().forEach(p -> {
            locations.put("process:" + p.pid(), new Location("process:" + p.pid(), p.pid(), p.containerId(), p.executable()));
            groups.put("process:" + p.pid(), p.category().label());
        });
        Map<List<String>, List<Observation.Library>> libraryGroups = new LinkedHashMap<>();
        Map<List<String>, Set<Location>> libraryLocations = new LinkedHashMap<>();
        Map<String, Assessment.Coverage> identities = new LinkedHashMap<>();
        if (report.assessment() != null) {
            report.assessment().coverage().stream().filter(c -> "CVE".equals(c.kind())).forEach(c -> identities.putIfAbsent(c.asset(), c));
        }
        for (var java : report.javaProcesses()) {
            var process = processes.get(java.pid());
            ServiceCategory category = process == null ? ServiceCategory.OTHER : process.category();
            for (var library : java.libraries()) {
                String asset = Assessment.libraryAsset(java.pid(), library);
                var location = new Location(asset, java.pid(), process == null ? "" : process.containerId(), library.path());
                locations.put(asset, location);
                groups.put(asset, category.label());
                libraryByAsset.put(asset, library);
                var identity = identities.get(asset);
                String name = identity == null || identity.packageName().isBlank() ? library.name() : identity.packageName();
                var key = List.of(category.name(), name, library.version());
                libraryGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(library);
                libraryLocations.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(location);
            }
        }
        Map<List<String>, ActionBuilder> actions = new LinkedHashMap<>();
        if (report.assessment() != null) {
            for (var finding : report.assessment().findings()) {
                if (!finding.actionable()) { continue; }
                var library = libraryByAsset.get(finding.asset());
                String name = finding.packageName().isBlank() && library != null ? library.name() : finding.packageName();
                String version = finding.version().isBlank() && library != null ? library.version() : finding.version();
                String group = groups.getOrDefault(finding.asset(), "기타");
                var key = List.of(finding.kind(), finding.id(), group, name, version, finding.status());
                var builder = actions.computeIfAbsent(key, k -> new ActionBuilder(finding, group, name, version));
                builder.add(finding, locations.getOrDefault(finding.asset(), new Location(finding.asset(), null, "", "")));
            }
        }
        List<Action> actionList = actions.values().stream().map(ActionBuilder::build)
                .sorted(Comparator.comparingInt((Action a) -> severity(a.severity())).thenComparing(Action::id)
                        .thenComparing(Action::serviceGroup).thenComparing(Action::packageName).thenComparing(Action::version)).toList();
        Map<List<String>, Set<String>> coverage = new LinkedHashMap<>();
        if (report.assessment() != null) {
            if (!report.assessment().coverage().isEmpty()) {
                report.assessment().coverage().forEach(c -> coverage.computeIfAbsent(List.of(c.kind(), c.status(), c.reason()), k -> new LinkedHashSet<>())
                        .add(c.asset() + "\n" + c.packageName() + "\n" + c.version()));
            } else {
                // Legacy snapshots retain all original rows in report.json; only presentation changes.
                report.assessment().findings().forEach(f -> coverage.computeIfAbsent(List.of(f.kind(), "점검 불가".equals(f.status()) ? "미판정" : f.status(), "이전 스냅샷 판정"), k -> new LinkedHashSet<>())
                        .add(f.asset() + ("CVE".equals(f.kind()) ? "" : "\n" + f.id())));
            }
        }
        var coverageList = coverage.entrySet().stream().map(e -> new CoverageCount(e.getKey().get(0), e.getKey().get(1), e.getKey().get(2), e.getValue().size())).toList();
        List<LibraryGroup> libraries = new ArrayList<>();
        var statesByAsset = report.assessment() == null ? Map.<String, String>of() : report.assessment().statuses();
        Map<String, Set<String>> idsByAsset = new LinkedHashMap<>();
        actionList.forEach(a -> a.locations().forEach(l -> idsByAsset.computeIfAbsent(l.asset(), k -> new LinkedHashSet<>()).add(a.id())));
        libraryGroups.forEach((key, values) -> {
            var assets = libraryLocations.get(key);
            List<String> states = report.assessment() == null ? List.of("미실시") : assets.stream().map(l -> statesByAsset.getOrDefault(l.asset(), "미판정")).toList();
            String status = states.contains("발견") ? "발견" : states.contains("의심") ? "의심" : states.contains("미판정") ? "미판정" : states.getFirst();
            var ids = assets.stream().flatMap(l -> idsByAsset.getOrDefault(l.asset(), Set.of()).stream()).distinct().sorted().toList();
            libraries.add(new LibraryGroup(ServiceCategory.valueOf(key.get(0)), key.get(1), key.get(2), status, ids,
                    values.stream().map(Observation.Library::evidence).distinct().toList(), List.copyOf(assets)));
        });
        Map<List<String>, Set<String>> issueGroups = new LinkedHashMap<>();
        report.issues().forEach(i -> issueGroups.computeIfAbsent(List.of(i.layer(), i.message()), k -> new LinkedHashSet<>()).add(i.target()));
        return new OperatorReport(actionList, coverageList, libraries, issueGroups.entrySet().stream()
                .map(e -> new IssueGroup(e.getKey().get(0), e.getKey().get(1), List.copyOf(e.getValue()))).toList());
    }

    private static int severity(String value) {
        return switch (value) { case "CRITICAL" -> 0; case "HIGH" -> 1; case "MODERATE", "MEDIUM" -> 2; case "LOW" -> 3; default -> 4; };
    }

    private static class ActionBuilder {
        final Assessment.Finding first;
        final String group, name, version;
        String severity;
        final Set<Location> locations = new LinkedHashSet<>();
        final Set<String> evidence = new LinkedHashSet<>(), recommendations = new LinkedHashSet<>(), references = new LinkedHashSet<>(), aliases = new LinkedHashSet<>();
        ActionBuilder(Assessment.Finding finding, String group, String name, String version) {
            this.first = finding; this.group = group; this.name = name; this.version = version; this.severity = finding.severity();
        }
        void add(Assessment.Finding finding, Location location) {
            locations.add(location); evidence.add(finding.evidence()); recommendations.add(finding.recommendation());
            if (!finding.reference().isBlank()) { references.add(finding.reference()); }
            aliases.addAll(finding.aliases());
            if (severity(finding.severity()) < severity(severity)) { severity = finding.severity(); }
        }
        Action build() { return new Action(first.kind(), first.id(), first.status(), severity, group, name, version, first.title(),
                List.copyOf(locations), List.copyOf(evidence), List.copyOf(recommendations), List.copyOf(references), List.copyOf(aliases)); }
    }
}
