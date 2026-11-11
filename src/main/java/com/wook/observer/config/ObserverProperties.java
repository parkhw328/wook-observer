package com.wook.observer.config;

import com.wook.observer.model.ServiceCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "observer")
public class ObserverProperties {
    private boolean dockerEnabled = true;
    @NotBlank
    private String dockerSocket = "/var/run/docker.sock";
    private boolean jvmAttachEnabled = true;
    private Duration commandTimeout = Duration.ofSeconds(3);
    @Min(1) @Max(65536)
    private int maxProcesses = 4096;
    @Min(1) @Max(4096)
    private int maxContainers = 256;
    @Min(1) @Max(1024)
    private int maxJavaProcesses = 64;
    @Min(1) @Max(4096)
    private int maxJarsPerProcess = 512;
    private Map<String, ServiceCategory> serviceOverrides = new LinkedHashMap<>();

    public boolean isDockerEnabled() { return dockerEnabled; }
    public void setDockerEnabled(boolean value) { dockerEnabled = value; }
    public String getDockerSocket() { return dockerSocket; }
    public void setDockerSocket(String value) { dockerSocket = value; }
    public boolean isJvmAttachEnabled() { return jvmAttachEnabled; }
    public void setJvmAttachEnabled(boolean value) { jvmAttachEnabled = value; }
    public Duration getCommandTimeout() { return commandTimeout; }
    public void setCommandTimeout(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(100)) < 0
                || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("command-timeout must be between 100ms and 30s");
        }
        commandTimeout = value;
    }
    public int getMaxProcesses() { return maxProcesses; }
    public void setMaxProcesses(int value) { maxProcesses = value; }
    public int getMaxContainers() { return maxContainers; }
    public void setMaxContainers(int value) { maxContainers = value; }
    public int getMaxJavaProcesses() { return maxJavaProcesses; }
    public void setMaxJavaProcesses(int value) { maxJavaProcesses = value; }
    public int getMaxJarsPerProcess() { return maxJarsPerProcess; }
    public void setMaxJarsPerProcess(int value) { maxJarsPerProcess = value; }
    public Map<String, ServiceCategory> getServiceOverrides() { return serviceOverrides; }
    public void setServiceOverrides(Map<String, ServiceCategory> value) { serviceOverrides = value; }
}
