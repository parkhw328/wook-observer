package com.wook.observer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Legacy local-only HTTP collector; the platform never exposes these routes. */
@Configuration
@Profile("collector")
@ConditionalOnWebApplication
public class CollectorSecurity {
    @Bean
    SecurityFilterChain collectorChain(HttpSecurity http, @org.springframework.beans.factory.annotation.Value("${server.address:127.0.0.1}") String address) throws Exception {
        if (!java.net.InetAddress.getByName(address).isLoopbackAddress()) {
            throw new IllegalStateException("The unauthenticated collector HTTP profile must bind to loopback only");
        }
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
