package com.wook.observer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WookObserverApplication {
    public static void main(String[] args) {
        JavaVersionPolicy.requireSupported(Runtime.version());
        SpringApplication application = new SpringApplication(WookObserverApplication.class);
        if (Arrays.stream(args).anyMatch(arg -> arg.startsWith("--observer.report-dir="))) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
