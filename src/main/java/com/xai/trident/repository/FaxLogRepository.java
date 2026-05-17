package com.xai.trident.repository;

import com.xai.trident.model.FaxLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FaxLogRepository extends JpaRepository<FaxLog, Long> {

    // Find logs by fax ID with pagination (for FaxEngineService and FaxController)
    @Query("SELECT fl FROM FaxLog fl WHERE fl.faxId = :faxId ORDER BY fl.timestamp DESC")
    Page<FaxLog> findByFaxId(@Param("faxId") String faxId, Pageable pageable);

    // Find logs by status with pagination (for AdminController stats)
    @Query("SELECT fl FROM FaxLog fl WHERE fl.status = :status ORDER BY fl.timestamp DESC")
    Page<FaxLog> findByStatus(@Param("status") String status, Pageable pageable);

    // Find logs within a time range with pagination (for AdminController analytics)
    @Query("SELECT fl FROM FaxLog fl WHERE fl.timestamp BETWEEN :start AND :end ORDER BY fl.timestamp DESC")
    Page<FaxLog> findByTimestampBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);

    // Count logs by status (for AdminController dashboard)
    @Query("SELECT fl.status, COUNT(fl) FROM FaxLog fl GROUP BY fl.status")
    List<Object[]> countByStatus();

    // Find recent logs by fax number with pagination (for FaxController user history)
    @Query("SELECT fl FROM FaxLog fl WHERE fl.faxNumber = :faxNumber ORDER BY fl.timestamp DESC")
    Page<FaxLog> findByFaxNumber(@Param("faxNumber") String faxNumber, Pageable pageable);

    /**
     * Count logs for a fax number. Used by SmartAssistService to score
     * candidate contacts. Replaces the previous pattern of calling
     * {@code findByFaxNumber(..., Pageable.unpaged()).getTotalElements()}
     * inside a loop, which (per candidate) loaded the whole history into
     * memory just to ask for its size (2.9).
     */
    long countByFaxNumber(String faxNumber);

    // Count logs by creator for analytics
    @Query("SELECT fl.createdBy, COUNT(fl) FROM FaxLog fl GROUP BY fl.createdBy")
    List<Object[]> countByCreatedBy();

    // Find logs by contact ID with pagination
    @Query("SELECT fl FROM FaxLog fl WHERE fl.contact.id = :contactId ORDER BY fl.timestamp DESC")
    Page<FaxLog> findByContactId(@Param("contactId") Long contactId, Pageable pageable);

    // Find logs with errors in a time range
    @Query("SELECT fl FROM FaxLog fl WHERE fl.errorMessage IS NOT NULL AND fl.timestamp BETWEEN :start AND :end ORDER BY fl.timestamp DESC")
    Page<FaxLog> findErrorsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);
}
