package com.aigreentick.services.wabaaccounts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async thread pool configuration
 *
 * webhookTaskExecutor:
 * - Used by @Async("webhookTaskExecutor") in WebhookService
 * - Processes incoming Meta webhook events in background
 * - Core: 5 threads, Max: 20 threads, Queue: 100 tasks
 * - Waits for tasks to finish on shutdown (graceful)
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "webhookTaskExecutor")
    public Executor webhookTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}