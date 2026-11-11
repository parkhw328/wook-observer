package com.wook.observer.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Observation(String version, Instant collectedAt, Host host, List<ProcessInfo> processes,
                          List<JavaInfo> javaProcesses, List<ContainerInfo> containers, List<Issue> issues,
                          Map<String, String> facts, Assessment assessment) {
    public Observation(String version, Instant collectedAt, Host host, List<ProcessInfo> processes,
                       List<JavaInfo> javaProcesses, List<ContainerInfo> containers, List<Issue> issues) {
        this(version, collectedAt, host, processes, javaProcesses, containers, issues, Map.of(), null);
    }

    public Observation assessed(Assessment result) {
        return new Observation(version, collectedAt, host, processes, javaProcesses, containers, issues, facts, result);
    }
    public record Host(String name, String os, String osVersion, String architecture, int processors,
                       long physicalMemoryBytes, String scope, String dockerVersion) { }

    public record ProcessInfo(long pid, long parentPid, String name, String executable, String user,
                              Instant startedAt, Long cpuMillis, Long residentBytes, Integer threads,
                              ServiceCategory category, String classificationEvidence, String containerId) { }

    public record JavaInfo(long pid, String status, Map<String, String> properties, List<Library> libraries) { }

    public record Library(String name, String version, String path, String evidence,
                          String license, String loadingStatus) { }

    public record ContainerInfo(String id, String name, String image, String imageId, String state,
                                long initPid, List<Long> processIds, List<String> ports,
                                List<String> networks, List<String> mounts, ServiceCategory category,
                                String classificationEvidence) { }

    public record Issue(String layer, String target, String message) { }
}
