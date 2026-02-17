package com.aigreentick.services.wabaaccounts.entity;

import com.aigreentick.services.wabaaccounts.constants.MessagingLimitTier;
import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity representing a WhatsApp Business phone number
 * Each phone number belongs to one WABA account
 */
@Entity
@Table(name = "waba_phone_numbers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WabaPhoneNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "waba_account_id", nullable = false)
    private Long wabaAccountId;

    @Column(name = "phone_number_id", nullable = false, unique = true, length = 100)
    private String phoneNumberId;

    @Column(name = "display_phone_number", length = 30)
    private String displayPhoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PhoneNumberStatus status = PhoneNumberStatus.ACTIVE;

    @Column(name = "verified_name", length = 100)
    private String verifiedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_rating", length = 20)
    private QualityRating qualityRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "messaging_limit_tier", length = 20)
    private MessagingLimitTier messagingLimitTier;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    // Relationship
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waba_account_id", insertable = false, updatable = false)
    private WabaAccount wabaAccount;

    /**
     * Check if phone number is active
     */
    public boolean isActive() {
        return PhoneNumberStatus.ACTIVE.equals(this.status);
    }

    /**
     * Check if quality rating is good (GREEN)
     */
    public boolean hasGoodQuality() {
        return QualityRating.GREEN.equals(this.qualityRating);
    }

    /**
     * Check if quality rating is at risk (YELLOW or RED)
     */
    public boolean isQualityAtRisk() {
        return QualityRating.YELLOW.equals(this.qualityRating) ||
                QualityRating.RED.equals(this.qualityRating);
    }

    /**
     * Get daily messaging limit based on tier
     */
    public int getDailyMessagingLimit() {
        if (messagingLimitTier == null) {
            return MessagingLimitTier.TIER_1K.getLimit();
        }
        return messagingLimitTier.getLimit();
    }
}