package com.xai.trident.repository;

import com.xai.trident.model.FaxMetadata;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FaxMetadataRepository extends JpaRepository<FaxMetadata, Long> {

    // Find metadata by fax log ID (used in FaxController and FaxEngineService)
    @Query("SELECT fm FROM FaxMetadata fm WHERE fm.faxLog.id = :faxLogId")
    Optional<FaxMetadata> findByFaxLogId(@Param("faxLogId") Long faxLogId);

    // Find metadata created after a specific date with pagination (for AdminController)
    @Query("SELECT fm FROM FaxMetadata fm WHERE fm.createdAt > :date")
    Page<FaxMetadata> findByCreatedAfter(@Param("date") LocalDateTime date, Pageable pageable);

    // Calculate average page count (for AdminController stats)
    @Query("SELECT AVG(fm.pageCount) FROM FaxMetadata fm")
    Double findAveragePageCount();

    /**
     * Total page count across every fax. Used by the admin dashboard.
     * <p>Returns {@code null} when the table is empty — JPQL {@code SUM}
     * follows SQL three-valued logic. Callers should coalesce to {@code 0}.
     * Replaces the previous {@code findAll().stream().mapToInt(...).sum()}
     * pattern, which loaded every row into memory (2.9).
     */
    @Query("SELECT SUM(fm.pageCount) FROM FaxMetadata fm")
    Long findTotalPageCount();

    /**
     * Total file size across every fax. Same caveats as
     * {@link #findTotalPageCount()}. Replaces the previous
     * {@code findAll().stream().mapToLong(...).sum()} pattern (2.9).
     */
    @Query("SELECT SUM(fm.fileSize) FROM FaxMetadata fm")
    Long findTotalFileSize();

    // Find metadata by file type with pagination (for analytics)
    @Query("SELECT fm FROM FaxMetadata fm WHERE fm.fileType = :fileType")
    Page<FaxMetadata> findByFileType(@Param("fileType") String fileType, Pageable pageable);

    // Count metadata by creator (for AdminController analytics)
    @Query("SELECT fm.createdBy, COUNT(fm) FROM FaxMetadata fm GROUP BY fm.createdBy")
    List<Object[]> countByCreatedBy();

    // Find metadata with large files (e.g., > 1MB) for optimization
    @Query("SELECT fm FROM FaxMetadata fm WHERE fm.fileSize > :sizeThreshold")
    Page<FaxMetadata> findLargeFiles(@Param("sizeThreshold") Long sizeThreshold, Pageable pageable);
}
