package com.aigreentick.services.wabaaccounts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread pool configuration for async tasks.
 *
 * TWO POOLS:
 *
 * onboardingTaskExecutor — used by OnboardingAsyncDispatcher
 * ──────────────────────────────────────────────────────────
 * Each task runs the full Meta API flow (8-12s peak, 2-4s typical).
 * corePoolSize=4: handles steady-state load without thread churn.
 * maxPoolSize=10: burst capacity for signup spikes.
 * queueCapacity=50: backlog before CallerRunsPolicy kicks in.
 * Raise queueCapacity if you see "http-nio-*" threads doing onboarding work.
 *
 * webhookTaskExecutor — used by WebhookService
 * ─────────────────────────────────────────────
 * Webhook processing is fast (simple DB writes, no external I/O).
 * Separate pool prevents webhook bursts from starving onboarding tasks.
 * corePoolSize=2 / maxPoolSize=5 is sufficient for typical Meta webhook volume.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "onboardingTaskExecutor")
    public Executor onboardingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("waba-onboarding-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Executor for WebhookService.processWebhookAsync().
     * Referenced as @Async("webhookTaskExecutor") in WebhookService.
     * Without this bean, Spring falls back to SimpleAsyncTaskExecutor
     * which creates a new thread per invocation — unbounded under load.
     */
    @Bean(name = "webhookTaskExecutor")
    public Executor webhookTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("waba-webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}