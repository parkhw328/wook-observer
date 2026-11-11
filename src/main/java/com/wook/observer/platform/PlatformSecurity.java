package com.wook.observer.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@Profile("!collector")
@EnableScheduling
public class PlatformSecurity {
    @Bean
    SecurityFilterChain platformChain(HttpSecurity http, Accounts accounts) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(new BCryptPasswordEncoder(12));
        http.authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/app.css", "/app.js", "/login.html", "/login.js", "/fonts/**", "/api/session", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.sendError(401, "로그인 정보를 확인하세요.")).permitAll())
                .logout(logout -> logout.logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .addFilterBefore(new AccountStateFilter(accounts), AuthorizationFilter.class);
        return http.build();
    }

    /** Password/role changes and disablement revoke existing sessions on their next request. */
    private static class AccountStateFilter extends OncePerRequestFilter {
        private final Accounts accounts;
        AccountStateFilter(Accounts accounts) { this.accounts = accounts; }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Accounts.Account original) {
                Accounts.Account current = (Accounts.Account) accounts.loadUserByUsername(original.username());
                if (!current.enabled() || current.revision() != original.revision()) {
                    if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
                    SecurityContextHolder.clearContext();
                    response.sendError(401, "계정이 변경되었습니다. 다시 로그인하세요.");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
