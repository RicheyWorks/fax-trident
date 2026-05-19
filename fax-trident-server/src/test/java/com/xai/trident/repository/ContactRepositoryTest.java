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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice tests for {@link ContactRepository}. Exercises every custom
 * query — both derived methods and explicit {@code @Query} JPQL — against
 * a Flyway-migrated H2 schema.
 *
 * <p>Why this matters: {@code ddl-auto: validate} catches column-level
 * drift but says nothing about whether the JPQL strings actually parse,
 * bind, and return the expected shape. The JPQL queries below were
 * previously unverified — a typo in a JOIN or a misnamed property would
 * fail only at runtime against production traffic.
 *
 * <p>{@code @DataJpaTest} wraps each test in a transaction that rolls
 * back, so per-method seed data doesn't bleed between tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:contactrepotest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
public class ContactRepositoryTest {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private FaxLogRepository faxLogRepository;

    private Contact persist(String name, String faxNumber) {
        Contact c = new Contact(name, faxNumber);
        return contactRepository.save(c);
    }

    @Test
    void findByFaxNumber_returnsExactMatch() {
        persist("Alice", "+15551110001");
        persist("Bob",   "+15551110002");

        Optional<Contact> found = contactRepository.findByFaxNumber("+15551110001");

        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().getName());
    }

    @Test
    void findByNameContainingIgnoreCase_matchesCaseInsensitive() {
        persist("Alice Anderson", "+15551110001");
        persist("Bob Brown",      "+15551110002");
        persist("Carol Carter",   "+15551110003");

        Page<Contact> hits = contactRepository.findByNameContainingIgnoreCase(
                "alice", PageRequest.of(0, 10));

        assertEquals(1, hits.getTotalElements());
        assertEquals("Alice Anderson", hits.getContent().get(0).getName());
    }

    @Test
    void findByFaxNumberContaining_returnsBoundedPage() {
        // Contact-suggestion path: substring match against fax_number, capped
        // by Pageable. Replaces the pre-fix findAll().filter pattern (2.9).
        persist("Alice", "+15551111111");
        persist("Bob",   "+15552221111");
        persist("Carol", "+15553334444");

        Page<Contact> hits = contactRepository.findByFaxNumberContaining(
                "1111", PageRequest.of(0, 200));

        assertEquals(2, hits.getTotalElements(), "two contacts contain '1111' in their fax number");
    }

    @Test
    void findByCreatedAfter_filtersOlderRecords() {
        persist("Alice", "+15551110001");
        // Every persisted contact gets @CreationTimestamp = "now" — using a
        // threshold in the past means everything matches. The opposite case
        // (threshold in the future, zero results) is what catches the case
        // where the column wiring is wrong and the WHERE clause silently
        // passes through everything.
        LocalDateTime future = LocalDateTime.now().plusYears(1);

        Page<Contact> recent = contactRepository.findByCreatedAfter(future, PageRequest.of(0, 10));

        assertEquals(0, recent.getTotalElements(), "no rows have createdAt in the future");
    }

    @Test
    void existsByFaxNumber_returnsTrueWhenPresent() {
        persist("Alice", "+15551110001");

        assertTrue(contactRepository.existsByFaxNumber("+15551110001"));
        assertFalse(contactRepository.existsByFaxNumber("+15559999999"));
    }

    @Test
    void countByCreatedBy_groupsCorrectly() {
        // Contact's @PrePersist hook reads SecurityContextHolder for the
        // current username; in a test context it falls back to "system"
        // (see Contact.prePersist). So every row in this test ends up with
        // createdBy="system", which still verifies the GROUP BY shape even
        // if there's only one bucket.
        persist("Alice", "+15551110001");
        persist("Bob",   "+15551110002");

        List<Object[]> grouped = contactRepository.countByCreatedBy();

        assertEquals(1, grouped.size(), "all rows share createdBy=system → one group");
        Object[] row = grouped.get(0);
        assertEquals("system", row[0]);
        // COUNT(*) is Long in JPA; H2 + Hibernate dialect agree on this.
        assertEquals(2L, ((Number) row[1]).longValue());
    }

    @Test
    void findActiveContactsBetween_distinctOverFaxLogJoin() {
        // JOIN c.faxLogs fl WHERE fl.timestamp BETWEEN :start AND :end — the
        // join is what makes this query worth exercising. Persist a contact,
        // attach a log via the audit-fix path (FaxLog owns the FK), and
        // verify the contact is reachable in the active window.
        Contact alice = persist("Alice", "+15551110001");
        FaxLog log = new FaxLog("fax_active_1", "sent", "+15551110001", "doc.pdf");
        log.setTimestamp(LocalDateTime.now());
        log.setContact(alice);
        faxLogRepository.save(log);

        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(1);
        LocalDateTime windowEnd   = LocalDateTime.now().plusMinutes(1);

        List<Contact> active = contactRepository.findActiveContactsBetween(windowStart, windowEnd);

        assertEquals(1, active.size());
        assertEquals("Alice", active.get(0).getName());
    }
}
