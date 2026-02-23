package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import com.aigreentick.services.wabaaccounts.entity.WabaPhoneNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for WABA Phone Number operations.
 *
 * ═══════════════════════════════════════════════════════════════
 * CHANGES in this version:
 *
 * 1. findByPhoneNumberIdAndStatus() — status-aware idempotency lookup
 *    Required by the two-transaction registration pattern.
 *    The service must distinguish between:
 *      PENDING            → registration started, Meta not yet called
 *      ACTIVE             → already fully registered (return it, don't retry)
 *      REGISTRATION_FAILED → Meta call failed, safe to retry
 *
 * 2. updatePhoneStatus() — targeted status update for TX 2
 *    Replaces entity load → field set → save pattern in the finalization step.
 *    Uses clearAutomatically + flushAutomatically (senior review fix) to prevent
 *    stale L1 cache reads and dirty-entity overwrites.
 *
 * 3. SENIOR REVIEW FIXES retained:
 *    - findByMetaPhoneIdWithOAuthAccount() uses Meta ID not DB PK
 *    - All @Modifying have clearAutomatically + flushAutomatically
 * ═══════════════════════════════════════════════════════════════
 */
@Repository
public interface WabaPhoneNumberRepository extends JpaRepository<WabaPhoneNumber, Long> {

    // ── READ ────────────────────────────────────────────────────

    List<WabaPhoneNumber> findByWabaAccountId(Long wabaAccountId);

    List<WabaPhoneNumber> findByWabaAccountIdAndStatus(Long wabaAccountId,
                                                       PhoneNumberStatus status);

    Optional<WabaPhoneNumber> findByPhoneNumberId(String phoneNumberId);

    /**
     * Status-aware idempotency check for the two-phase registration pattern.
     *
     * The registration saga needs to know not just whether a phone row exists,
     * but what state it is in so it can decide the correct action:
     *
     *   status == ACTIVE             → already done, return it
     *   status == PENDING            → TX 1 committed but TX 2 not yet run
     *                                  (retry the Meta call + TX 2)
     *   status == REGISTRATION_FAILED → Meta previously rejected it; safe to retry
     *   empty                        → phone is new, proceed with TX 1
     *
     * This replaces the naive existsByPhoneNumberId() check that could not
     * distinguish between these cases.
     */
    Optional<WabaPhoneNumber> findByPhoneNumberIdAndStatus(String phoneNumberId,
                                                           PhoneNumberStatus status);

    boolean existsByPhoneNumberId(String phoneNumberId);

    long countByWabaAccountId(Long wabaAccountId);

    /**
     * SENIOR FIX — Meta phoneNumberId replaces DB primary key.
     *
     * JOIN FETCH loads WabaPhoneNumber → WabaAccount → MetaOAuthAccount
     * in one SQL JOIN. Zero additional queries for token resolution.
     *
     * Uses Meta's phoneNumberId (String) — not the DB pk (Long).
     * Callers already hold the Meta ID from the SDK/frontend.
     */
    @Query("""
            SELECT p FROM WabaPhoneNumber p
            JOIN FETCH p.wabaAccount w
            JOIN FETCH w.metaOAuthAccount
            WHERE p.phoneNumberId = :metaPhoneId
            """)
    Optional<WabaPhoneNumber> findByMetaPhoneIdWithOAuthAccount(
            @Param("metaPhoneId") String metaPhoneId);

    /**
     * Bulk load existing phones for a WABA by their Meta phone IDs.
     * Replaces N individual findByPhoneNumberId() calls in the sync loop
     * with a single IN query.
     */
    @Query("""
            SELECT p FROM WabaPhoneNumber p
            WHERE p.wabaAccountId = :wabaAccountId
            AND p.phoneNumberId IN :phoneNumberIds
            """)
    List<WabaPhoneNumber> findAllByWabaAccountIdAndPhoneNumberIdIn(
            @Param("wabaAccountId") Long wabaAccountId,
            @Param("phoneNumberIds") List<String> phoneNumberIds);

    /**
     * Find phone numbers with quality issues (YELLOW or RED).
     */
    @Query("SELECT p FROM WabaPhoneNumber p " +
            "WHERE p.qualityRating IN :ratings AND p.status = 'ACTIVE'")
    List<WabaPhoneNumber> findByQualityRatingIn(@Param("ratings") List<QualityRating> ratings);

    // ── WRITE ───────────────────────────────────────────────────

    /**
     * Targeted status update — used by TX 2 of the registration saga.
     *
     * Avoids the load-then-set-then-save pattern (which requires an
     * extra SELECT and holds the entity in the persistence context).
     *
     * clearAutomatically = true → evicts entity from L1 cache after UPDATE
     *   so subsequent reads in the same tx see the new status, not stale cache.
     *
     * flushAutomatically = true → flushes any dirty entity state before the
     *   UPDATE runs, preventing a later auto-flush from silently overwriting
     *   the status the UPDATE just set.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WabaPhoneNumber p " +
            "SET p.status = :newStatus " +
            "WHERE p.phoneNumberId = :phoneNumberId " +
            "AND p.status = :expectedStatus")
    int updatePhoneStatus(@Param("phoneNumberId") String phoneNumberId,
                          @Param("expectedStatus") PhoneNumberStatus expectedStatus,
                          @Param("newStatus") PhoneNumberStatus newStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WabaPhoneNumber p " +
            "SET p.qualityRating = :rating " +
            "WHERE p.phoneNumberId = :phoneNumberId")
    int updateQualityRating(@Param("phoneNumberId") String phoneNumberId,
                            @Param("rating") QualityRating rating);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WabaPhoneNumber p " +
            "SET p.status = :status " +
            "WHERE p.phoneNumberId = :phoneNumberId")
    int updateStatusByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId,
                                    @Param("status") PhoneNumberStatus status);

    /**
     * Find phone numbers by WABA string ID (via JOIN).
     */
    @Query("""
            SELECT p FROM WabaPhoneNumber p
            JOIN p.wabaAccount w
            WHERE w.wabaId = :wabaId
            """)
    List<WabaPhoneNumber> findByWabaId(@Param("wabaId") String wabaId);
}