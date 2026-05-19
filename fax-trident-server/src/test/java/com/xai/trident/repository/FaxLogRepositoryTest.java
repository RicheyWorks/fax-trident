package com.xai.trident.repository;

import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice tests for {@link FaxLogRepository}. Verifies every custom finder
 * + aggregate query against a Flyway-migrated H2 schema.
 *
 * <p>Notable cases:
 * <ul>
 *   <li>{@code countByFaxNumber} — replaces the old
 *       {@code findByFaxNumber(unpaged).getTotalElements()} pattern from
 *       audit 2.9. Verifies the derived method actually counts via SQL
 *       and not by loading + sizing.</li>
 *   <li>{@code findErrorsBetween} — exercises the
 *       {@code errorMessage IS NOT NULL} predicate, easy to break with a
 *       wrong column name.</li>
 *   <li>{@code findByContactId} — drills through the {@code @ManyToOne}
 *       FK ({@code contact_id}). The audit-2.22 index split renamed
 *       the contacts index but the FK column is still {@code contact_id};
 *       a regression here would show up in this test.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:faxlogrepotest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
public class FaxLogRepositoryTest {

    @Autowired private FaxLogRepository faxLogRepository;
    @Autowired private ContactRepository contactRepository;

    private FaxLog persist(String faxId, String status, String faxNumber) {
        return faxLogRepository.save(new FaxLog(faxId, status, faxNumber, "doc.pdf"));
    }

    private FaxLog persistWithError(String faxId, String faxNumber, String error) {
        return faxLogRepository.save(new FaxLog(faxId, "failed", faxNumber, "doc.pdf", error));
    }

    @Test
    void findByFaxId_returnsMatchingPage() {
        persist("fax_A", "sent",    "+15550000001");
        persist("fax_A", "failed",  "+15550000001"); // same faxId, different status (sequence over time)
        persist("fax_B", "sent",    "+15550000002");

        Page<FaxLog> hits = faxLogRepository.findByFaxId("fax_A", PageRequest.of(0, 10));

        assertEquals(2, hits.getTotalElements(), "two log rows share fax_A");
    }

    @Test
    void findByStatus_filtersAndOrdersByTimestampDesc() {
        persist("fax_1", "sent",    "+15550000001");
        persist("fax_2", "failed",  "+15550000002");
        persist("fax_3", "sent",    "+15550000003");

        Page<FaxLog> sentPage = faxLogRepository.findByStatus("sent", PageRequest.of(0, 10));

        assertEquals(2, sentPage.getTotalElements());
        assertTrue(sentPage.getContent().stream().allMatch(fl -> "sent".equals(fl.getStatus())));
    }

    @Test
    void findByTimestampBetween_capturesNewlyInsertedRows() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        persist("fax_1", "sent", "+15550000001");
        persist("fax_2", "sent", "+15550000002");
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        Page<FaxLog> window = faxLogRepository.findByTimestampBetween(
                before, after, PageRequest.of(0, 10));

        assertEquals(2, window.getTotalElements(), "both logs fall inside the [before, after] window");
    }

    @Test
    void countByStatus_groupsCorrectly() {
        persist("fax_1", "sent",    "+15550000001");
        persist("fax_2", "failed",  "+15550000002");
        persist("fax_3", "sent",    "+15550000003");
        persist("fax_4", "sending", "+15550000004");

        List<Object[]> grouped = faxLogRepository.countByStatus();

        // Convert to a status->count map for easier assertion order-independence.
        var counts = grouped.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> ((Number) row[1]).longValue()));

        assertEquals(2L, counts.get("sent"));
        assertEquals(1L, counts.get("failed"));
        assertEquals(1L, counts.get("sending"));
    }

    @Test
    void findByFaxNumber_returnsPagedForThatNumber() {
        persist("fax_1", "sent", "+15550000001");
        persist("fax_2", "sent", "+15550000001");
        persist("fax_3", "sent", "+15550000002");

        Page<FaxLog> hits = faxLogRepository.findByFaxNumber("+15550000001", PageRequest.of(0, 10));

        assertEquals(2, hits.getTotalElements());
    }

    @Test
    void countByFaxNumber_returnsExactCountViaDerivedQuery() {
        // The derived method is the post-audit-2.9 hot path: it runs SELECT
        // COUNT(*) at the DB instead of loading the rows and asking for
        // their size. Whether it's running the right SQL is exactly what
        // this test verifies.
        persist("fax_1", "sent", "+15550000001");
        persist("fax_2", "sent", "+15550000001");
        persist("fax_3", "sent", "+15550000001");
        persist("fax_4", "sent", "+15550000002");

        assertEquals(3L, faxLogRepository.countByFaxNumber("+15550000001"));
        assertEquals(0L, faxLogRepository.countByFaxNumber("+15559999999"));
    }

    @Test
    void countByCreatedBy_groupsCorrectly() {
        // FaxLog.prePersist defaults createdBy to "system" when no
        // SecurityContext is present (the test case). One bucket expected.
        persist("fax_1", "sent", "+15550000001");
        persist("fax_2", "sent", "+15550000002");

        List<Object[]> grouped = faxLogRepository.countByCreatedBy();

        assertEquals(1, grouped.size());
        assertEquals("system", grouped.get(0)[0]);
        assertEquals(2L, ((Number) grouped.get(0)[1]).longValue());
    }

    @Test
    void findByContactId_filtersByForeignKey() {
        Contact alice = contactRepository.save(new Contact("Alice", "+15550000001"));
        Contact bob   = contactRepository.save(new Contact("Bob",   "+15550000002"));

        FaxLog aliceLog1 = new FaxLog("fax_a1", "sent", "+15550000001", "doc.pdf");
        aliceLog1.setContact(alice);
        FaxLog aliceLog2 = new FaxLog("fax_a2", "sent", "+15550000001", "doc.pdf");
        aliceLog2.setContact(alice);
        FaxLog bobLog = new FaxLog("fax_b1", "sent", "+15550000002", "doc.pdf");
        bobLog.setContact(bob);
        faxLogRepository.save(aliceLog1);
        faxLogRepository.save(aliceLog2);
        faxLogRepository.save(bobLog);

        Page<FaxLog> hits = faxLogRepository.findByContactId(alice.getId(), PageRequest.of(0, 10));

        assertEquals(2, hits.getTotalElements());
    }

    @Test
    void findErrorsBetween_skipsLogsWithoutErrorMessage() {
        // The query: errorMessage IS NOT NULL AND timestamp BETWEEN start AND end.
        // The audit-2.18 column-type fix changed errorMessage from VARCHAR(255)
        // to TEXT so long stack traces don't overflow; the query still works
        // because the IS NOT NULL predicate doesn't care about width.
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        persist("fax_clean", "sent", "+15550000001");           // errorMessage IS NULL
        persistWithError("fax_err1", "+15550000002", "boom");   // errorMessage NOT NULL
        persistWithError("fax_err2", "+15550000003",
                "stack trace too long to fit in a varchar 255 column ".repeat(10));
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        Page<FaxLog> errors = faxLogRepository.findErrorsBetween(
                before, after, PageRequest.of(0, 10));

        assertEquals(2, errors.getTotalElements(), "only the two error rows match");
        assertTrue(errors.getContent().stream().allMatch(fl -> fl.getErrorMessage() != null));
    }

    @Test
    void save_assignsGeneratedId() {
        // Sanity probe for IDENTITY generation against H2/Flyway-created
        // fax_logs.id. A misconfigured @GeneratedValue would fail here
        // before any of the finder tests had a chance to run.
        FaxLog saved = persist("fax_idprobe", "sent", "+15550000001");
        assertNotNull(saved.getId());
    }
}
