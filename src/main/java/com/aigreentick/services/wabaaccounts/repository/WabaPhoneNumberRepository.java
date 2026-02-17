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
 * Repository for WABA Phone Number operations
 */
@Repository
public interface WabaPhoneNumberRepository extends JpaRepository<WabaPhoneNumber, Long> {

    /**
     * Find all phone numbers for a WABA
     */
    List<WabaPhoneNumber> findByWabaAccountId(Long wabaAccountId);

    /**
     * Find active phone numbers for a WABA
     */
    List<WabaPhoneNumber> findByWabaAccountIdAndStatus(Long wabaAccountId, PhoneNumberStatus status);

    /**
     * Find by Meta phone number ID
     */
    Optional<WabaPhoneNumber> findByPhoneNumberId(String phoneNumberId);

    /**
     * Check if phone number already registered
     */
    boolean existsByPhoneNumberId(String phoneNumberId);

    /**
     * Find phone numbers with quality issues (YELLOW or RED)
     */
    @Query("SELECT p FROM WabaPhoneNumber p WHERE p.qualityRating IN :ratings AND p.status = 'ACTIVE'")
    List<WabaPhoneNumber> findByQualityRatingIn(@Param("ratings") List<QualityRating> ratings);

    /**
     * Count phone numbers per WABA
     */
    long countByWabaAccountId(Long wabaAccountId);

    /**
     * Update quality rating by phone number ID
     */
    @Modifying
    @Query("UPDATE WabaPhoneNumber p SET p.qualityRating = :rating WHERE p.phoneNumberId = :phoneNumberId")
    int updateQualityRating(@Param("phoneNumberId") String phoneNumberId,
                            @Param("rating") QualityRating rating);

    /**
     * Update status by phone number ID
     */
    @Modifying
    @Query("UPDATE WabaPhoneNumber p SET p.status = :status WHERE p.phoneNumberId = :phoneNumberId")
    int updateStatusByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId,
                                    @Param("status") PhoneNumberStatus status);

    /**
     * Find phone numbers by WABA ID (using wabaId string, not FK)
     */
    @Query("SELECT p FROM WabaPhoneNumber p " +
            "JOIN p.wabaAccount w " +
            "WHERE w.wabaId = :wabaId")
    List<WabaPhoneNumber> findByWabaId(@Param("wabaId") String wabaId);
}