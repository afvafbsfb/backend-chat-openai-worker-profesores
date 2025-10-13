package com.workers.profesores.chat.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Value("${backend.debug:false}")
    private boolean backendDebug;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Desactivar CSRF para llamadas API y permitir /actuator/health e /info sin autenticación.
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> {
                if (backendDebug) {
                    // In debug mode allow access to debug endpoints
                    auth.requestMatchers("/actuator/health", "/actuator/info", "/debug/**").permitAll();
                } else {
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            // El resource server por defecto usará el JwtDecoder configurado (DevJwtConfig en dev)
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));

        return http.build();
    }
}
