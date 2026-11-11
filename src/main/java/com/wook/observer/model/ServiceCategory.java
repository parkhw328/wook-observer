package com.wook.observer.model;

public enum ServiceCategory {
    APPLICATION("application", "애플리케이션"),
    WEB("web", "웹"),
    DATABASE("database", "데이터베이스"),
    CACHE("cache", "캐시"),
    MESSAGING("messaging", "메시징"),
    INFRASTRUCTURE("infrastructure", "인프라"),
    OTHER("other", "기타");

    private final String directory;
    private final String label;

    ServiceCategory(String directory, String label) {
        this.directory = directory;
        this.label = label;
    }

    public String directory() { return directory; }
    public String label() { return label; }
}
