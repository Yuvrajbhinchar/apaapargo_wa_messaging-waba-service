package com.aigreentick.services.wabaaccounts;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main application class for WABA (WhatsApp Business Account) Service
 *
 * This microservice handles:
 * - Meta OAuth token management
 * - WABA account lifecycle management
 * - Phone number registration and verification
 * - Webhook processing for Meta events (account status, quality updates)
 * - Project-WABA account mapping
 *
 * Integration: Direct Meta Cloud API (Tech Partner)
 *
 * @author AiGreenTick Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@OpenAPIDefinition(
        info = @Info(
                title = "WABA Service API",
                version = "1.0.0",
                description = "WhatsApp Business Account Management Service for AiGreenTick Platform. " +
                        "Direct Meta Cloud API integration (Tech Partner).",
                contact = @Contact(
                        name = "AiGreenTick Support",
                        email = "support@aigreentick.com"
                )
        ),
        servers = {
                @Server(url = "http://localhost:8081", description = "Local Development"),
                @Server(url = "https://api.aigreentick.com", description = "Production")
        }
)
public class WabaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WabaServiceApplication.class, args);
    }
}