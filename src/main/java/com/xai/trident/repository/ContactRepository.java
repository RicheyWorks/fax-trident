package com.xai.trident.repository;

import com.xai.trident.model.Contact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    // Find contact by fax number (used in FaxEngineService)
    Optional<Contact> findByFaxNumber(String faxNumber);

    // Find contacts by partial name (case-insensitive) with pagination
    @Query("SELECT c FROM Contact c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    Page<Contact> findByNameContainingIgnoreCase(@Param("name") String name, Pageable pageable);

    /**
     * Find contacts whose fax number contains the given substring, paged.
     * Replaces the previous {@code findAll().stream().filter(...)} pattern in
     * {@code SmartAssistService} that loaded every contact into memory before
     * filtering (2.9). The query still does a {@code LIKE '%x%'} table scan,
     * but at least the result set is bounded by the Pageable.
     */
    @Query("SELECT c FROM Contact c WHERE c.faxNumber LIKE CONCAT('%', :partial, '%')")
    Page<Contact> findByFaxNumberContaining(@Param("partial") String partial, Pageable pageable);

    // findByOrganizationContainingIgnoreCase removed — defined but never
    // called (audit 2.20). Reintroduce as needed.

    // Find contacts created after a specific date with pagination
    @Query("SELECT c FROM Contact c WHERE c.createdAt > :date")
    Page<Contact> findByCreatedAfter(@Param("date") LocalDateTime date, Pageable pageable);

    // Check if fax number already exists
    boolean existsByFaxNumber(String faxNumber);

    // Count contacts by creator for analytics
    @Query("SELECT c.createdBy, COUNT(c) FROM Contact c GROUP BY c.createdBy")
    List<Object[]> countByCreatedBy();

    // Find contacts with fax activity in a time range
    @Query("SELECT DISTINCT c FROM Contact c JOIN c.faxLogs fl WHERE fl.timestamp BETWEEN :start AND :end")
    List<Contact> findActiveContactsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
