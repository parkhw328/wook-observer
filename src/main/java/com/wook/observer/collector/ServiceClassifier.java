package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.ServiceCategory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ServiceClassifier {
    private final ObserverProperties properties;

    public ServiceClassifier(ObserverProperties properties) {
        this.properties = properties;
    }

    public Classification classify(String key, String executableOrImage) {
        ServiceCategory override = properties.getServiceOverrides().get(key);
        if (override != null) {
            return new Classification(override, "설정: " + key);
        }
        String name = executableOrImage.toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf('/') + 1).split("[:@]", 2)[0];
        ServiceCategory category;
        if (Set.of("postgres", "postgresql", "mysqld", "mysql", "mariadb", "mongod", "mongo", "oracle").contains(name)) {
            category = ServiceCategory.DATABASE;
        } else if (Set.of("redis", "redis-server", "valkey", "valkey-server", "memcached").contains(name)) {
            category = ServiceCategory.CACHE;
        } else if (Set.of("nginx", "httpd", "apache2", "haproxy", "caddy", "traefik").contains(name)) {
            category = ServiceCategory.WEB;
        } else if (Set.of("kafka", "rabbitmq", "rabbitmq-server", "nats", "activemq", "pulsar").contains(name)) {
            category = ServiceCategory.MESSAGING;
        } else if (Set.of("dockerd", "containerd", "systemd", "prometheus", "grafana", "node_exporter", "sshd").contains(name)) {
            category = ServiceCategory.INFRASTRUCTURE;
        } else if (Set.of("java", "java.exe", "node", "python", "python3", "gunicorn", "uvicorn", "tomcat", "openjdk", "eclipse-temurin").contains(name)) {
            category = ServiceCategory.APPLICATION;
        } else {
            category = ServiceCategory.OTHER;
        }
        return new Classification(category, "실행 파일/이미지 이름 휴리스틱: " + name);
    }

    public record Classification(ServiceCategory category, String evidence) { }
}
