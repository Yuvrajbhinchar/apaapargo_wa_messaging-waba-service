package com.aigreentick.services.wabaaccounts.service.OnboardingModel;

import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;

public record PersistedOnboardingData(WabaAccount waba, MetaOAuthAccount oauthAccount) {
}
