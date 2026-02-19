package com.aigreentick.services.wabaaccounts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread pool configuration for async onboarding tasks.
 *
 * onboardingTaskExecutor — used by OnboardingOrchestrator
 * ─────────────────────────────────────────────────────────
 * Each task runs the full Meta API flow (8–12s), so we size the pool
 * conservatively. You almost certainly don't want 100 simultaneous
 * Meta API connections. Start at corePoolSize=4 and tune from there.
 *
 * Queue capacity of 50 means we can backlog 50 tasks before new
 * enqueue calls get the CallerRunsPolicy (runs on the controller thread,
 * defeating the async goal). Raise it if your onboarding volume spikes.
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
}