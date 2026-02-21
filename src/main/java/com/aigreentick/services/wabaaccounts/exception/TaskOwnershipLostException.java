package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown when a saga worker detects it no longer owns the task.
 *
 * This is NOT an error — it means the task was cancelled by the user,
 * reset by the scheduler, or completed by another worker. The worker
 * should stop immediately and discard its in-flight work.
 *
 * Caught in OnboardingAsyncDispatcher — never propagated to markFailed()
 * because the task is already in a terminal/reset state.
 */
public class TaskOwnershipLostException extends RuntimeException {

    private final Long taskId;
    private final String detectedStatus;

    public TaskOwnershipLostException(Long taskId, String detectedStatus) {
        super("Task " + taskId + " ownership lost — current status: " + detectedStatus);
        this.taskId = taskId;
        this.detectedStatus = detectedStatus;
    }

    public Long getTaskId() { return taskId; }
    public String getDetectedStatus() { return detectedStatus; }
}

