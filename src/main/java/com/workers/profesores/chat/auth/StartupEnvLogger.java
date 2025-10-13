package com.workers.profesores.chat.auth;

import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;


@Component
public class StartupEnvLogger {

    @Autowired
    private Environment springEnv;

    private String mask(String v) {
        if (v == null) return "<null>";
        if (v.isBlank()) return "<blank>";
        try {
            String clean = v.trim();
            if (clean.length() <= 8) return clean.substring(0, Math.min(4, clean.length())) + "...";
            return clean.substring(0, 4) + "...";
        } catch (Exception e) {
            return "<error>";
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        System.out.println("[StartupEnvLogger][INFO] Application started - resolved Spring properties (masked where appropriate):");
        try {
            // Print relevant properties resolved by Spring environment
            String[] sysProps = new String[] {"jwt.delegation.secret", "openai.api.key", "backend.debug", "spring.profiles.active", "server.port", "academia.api.baseurl"};
            for (String p : sysProps) {
                String v2 = springEnv.getProperty(p);
                String out2 = (p.toLowerCase().contains("secret") || p.toLowerCase().contains("key") || p.toLowerCase().contains("pass")) ? mask(v2) : (v2 == null ? "<null>" : v2);
                System.out.println("[StartupEnvLogger][PROP.spring] " + p + "=" + out2);
            }
        } catch (Exception e) {
            System.out.println("[StartupEnvLogger][ERROR] Failed to dump Spring properties: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}
