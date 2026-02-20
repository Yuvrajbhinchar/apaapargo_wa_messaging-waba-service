package com.aigreentick.services.wabaaccounts.service.OnboardingModel;

public record TokenResult(String accessToken, long expiresIn, boolean isLongLived) {
}
