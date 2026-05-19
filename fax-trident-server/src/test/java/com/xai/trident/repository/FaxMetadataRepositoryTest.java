package com.xai.trident.repository;

import com.xai.trident.model.FaxLog;
import com.xai.trident.model.FaxMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice tests for {@link FaxMetadataRepository}. Exercises every custom
 * finder and aggregate query, including two SUM queries whose
 * null-on-empty contract callers must coalesce on.
 *
 * <p>Notable cases:
 * <ul>
 *   <li>{@link FaxMetadataRepository#findTotalPageCount()} and
 *       {@link FaxMetadataRepository#findTotalFileSize()} —
 *       SQL {@code SUM} returns {@code null} on an empty table, not zero.
 *       The admin dashboard relies on this and null-coalesces (audit 2.9).
 *       The {@code empty_table_returns_null} cases below pin the contract
 *       so a future change to {@code COALESCE(...)} would be flagged.</li>
 *   <li>{@link FaxMetadataRepository#findByFaxLogId(Long)} — drills through
 *       the {@code fax_log_id} foreign key, which is NOT NULL per the
 *       Flyway migration. A misconfigured join column would surface here.</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} wraps each method in a rolled-back transaction,
 * so the "empty table" assertions are reliable even when other tests in
 * this class persist data.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:faxmetarepotest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.show-sql=false",
})
@EntityScan(basePackages = "com.xai.trident.model")
@EnableJpaRepositories(basePackages = "com.xai.trident.repository")
public class FaxMetadataRepositoryTest {

    @Autowired private FaxMetadataRepository faxMetadataRepository;
    @Autowired private FaxLogRepository faxLogRepository;

    /**
     * FaxMetadata.fax_log_id is NOT NULL, so every test that persists a
     * metadata row needs a real FaxLog to point at. This helper creates +
     * persists a no-frills log and returns it.
     */
    private FaxLog seedLog(String faxId) {
        return faxLogRepository.save(new FaxLog(faxId, "sent", "+15550000001", "doc.pdf"));
    }

    private FaxMetadata persist(FaxLog log, String fileName, int pages, String type, long size) {
        FaxMetadata md = new FaxMetadata(fileName, pages, type, size);
        md.setFaxLog(log);
        return faxMetadataRepository.save(md);
    }

    @Test
    void findByFaxLogId_returnsMatchingRow() {
        FaxLog log = seedLog("fax_X");
        persist(log, "doc.pdf", 3, "PDF", 1024L);

        Optional<FaxMetadata> found = faxMetadataRepository.findByFaxLogId(log.getId());

        assertTrue(found.isPresent());
        assertEquals("doc.pdf", found.get().getFileName());
    }

    @Test
    void findByCreatedAfter_filtersByCreatedAtColumn() {
        FaxLog log = seedLog("fax_X");
        persist(log, "doc.pdf", 1, "PDF", 100L);

        Page<FaxMetadata> future = faxMetadataRepository.findByCreatedAfter(
                LocalDateTime.now().plusYears(1), PageRequest.of(0, 10));
        assertEquals(0, future.getTotalElements(), "nothing was created in the future");

        Page<FaxMetadata> past = faxMetadataRepository.findByCreatedAfter(
                LocalDateTime.now().minusYears(1), PageRequest.of(0, 10));
        assertEquals(1, past.getTotalElements(), "the row was created after a year ago");
    }

    @Test
    void findAveragePageCount_computesAverage() {
        FaxLog log = seedLog("fax_X");
        persist(log, "a.pdf", 2, "PDF", 100L);
        persist(log, "b.pdf", 4, "PDF", 200L);
        persist(log, "c.pdf", 6, "PDF", 300L);

        Double avg = faxMetadataRepository.findAveragePageCount();

        assertNotNull(avg);
        assertEquals(4.0, avg, 0.001, "average of 2, 4, 6 is 4");
    }

    @Test
    void findTotalPageCount_sumsAcrossRows() {
        FaxLog log = seedLog("fax_X");
        persist(log, "a.pdf", 2, "PDF", 100L);
        persist(log, "b.pdf", 5, "PDF", 200L);
        persist(log, "c.pdf", 8, "PDF", 300L);

        Long total = faxMetadataRepository.findTotalPageCount();
        assertNotNull(total);
        assertEquals(15L, total);
    }

    @Test
    void findTotalPageCount_returnsNullOnEmpty() {
        // SQL SUM over an empty result set is NULL. The admin dashboard's
        // null-coalesce (audit 2.9 fix) depends on this contract — switching
        // the query to COALESCE(SUM(...), 0) would silently change it to
        // return 0, which then accidentally hides "the table is empty"
        // from any caller that distinguishes the two states. Pin it.
        assertNull(faxMetadataRepository.findTotalPageCount(),
                "SUM over empty table must be null, not 0");
    }

    @Test
    void findTotalFileSize_sumsAcrossRows() {
        FaxLog log = seedLog("fax_X");
        persist(log, "a.pdf", 1, "PDF", 1000L);
        persist(log, "b.pdf", 1, "PDF", 2500L);

        Long total = faxMetadataRepository.findTotalFileSize();
        assertNotNull(total);
        assertEquals(3500L, total);
    }

    @Test
    void findTotalFileSize_returnsNullOnEmpty() {
        assertNull(faxMetadataRepository.findTotalFileSize(),
                "SUM(fileSize) over empty table must be null, not 0");
    }

    @Test
    void findByFileType_filtersByExactType() {
        FaxLog log = seedLog("fax_X");
        persist(log, "a.pdf",  1, "PDF",  100L);
        persist(log, "b.tiff", 1, "TIFF", 100L);
        persist(log, "c.pdf",  1, "PDF",  100L);

        Page<FaxMetadata> pdfs = faxMetadataRepository.findByFileType("PDF", PageRequest.of(0, 10));

        assertEquals(2, pdfs.getTotalElements());
        assertTrue(pdfs.getContent().stream().allMatch(md -> "PDF".equals(md.getFileType())));
    }

    @Test
    void countByCreatedBy_groupsCorrectly() {
        FaxLog log = seedLog("fax_X");
        persist(log, "a.pdf", 1, "PDF", 100L);
        persist(log, "b.pdf", 1, "PDF", 200L);

        List<Object[]> grouped = faxMetadataRepository.countByCreatedBy();
        var counts = grouped.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> ((Number) row[1]).longValue()));

        assertEquals(2L, counts.get("system"));
    }

    @Test
    void findLargeFiles_filtersByThreshold() {
        FaxLog log = seedLog("fax_X");
        persist(log, "small.pdf", 1, "PDF", 500L);
        persist(log, "big.pdf",   1, "PDF", 5_000L);
        persist(log, "huge.pdf",  1, "PDF", 50_000L);

        Page<FaxMetadata> over1k = faxMetadataRepository.findLargeFiles(1_000L, PageRequest.of(0, 10));

        assertEquals(2, over1k.getTotalElements(), "big and huge exceed 1000 bytes");
    }
}
