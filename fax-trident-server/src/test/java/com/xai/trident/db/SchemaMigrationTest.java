package com.xai.trident.db;

import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
import com.xai.trident.model.FaxMetadata;
import com.xai.trident.model.User;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catches entity / migration drift.
 *
 * <p>This is the first test landed against the Flyway-managed schema (audit
 * tech-debt #4). It does two things, in this order:
 *
 * <ol>
 *   <li><b>Loads the Spring context</b> with {@code spring.jpa.hibernate.ddl-auto=validate}
 *       and {@code spring.flyway.enabled=true}. The fact that the context loads
 *       at all is the headline assertion: Flyway successfully applied
 *       {@code V1__initial_schema.sql} against H2 (in PostgreSQL mode), and
 *       Hibernate then walked every {@code @Entity} and verified the table /
 *       column / type / nullability match. A new {@code @Column} added to an
 *       entity without a corresponding {@code V<n>__...sql} migration fails
 *       this test at context load.</li>
 *   <li><b>Round-trips every entity</b>. Persists a sample, reads it back, and
 *       asserts the identity / lookup. This catches drift that bare
 *       {@code validate} misses — column-name casing surprises, custom
 *       converters that quietly stopped working, FK constraints firing
 *       backwards, etc. {@code @DataJpaTest} wraps each method in a
 *       transaction that rolls back, so the entities don't leak between
 *       methods.</li>
 * </ol>
 *
 * <p><b>What this test doesn't catch:</b> Hibernate {@code validate} ignores
 * indexes. A missing index in a migration won't fail here. The 2.22 finding
 * (duplicate {@code idx_fax_number} silently leaving {@code fax_logs.faxNumber}
 * unindexed in prod) is the canonical example — the previous Hibernate
 * auto-create generated the {@code CREATE INDEX}, which Postgres rejected, and
 * neither the build nor a validate-based test would have flagged it. The new
 * V1 migration creates the indexes explicitly, and the related duplicate-name
 * check is a one-time {@code grep} (see 2.22 verification notes), not an
 * automated test. Worth adding a separate Postgres-backed index-presence test
 * via Testcontainers when the test plan grows there.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // Use H2 in PostgreSQL mode so the V1 SQL (which targets prod's
        // Postgres dialect) runs without dialect-specific syntax errors.
        "spring.datasource.url=jdbc:h2:mem:schemamigtest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // The point of this test. Override the @DataJpaTest default
        // (create-drop) so Hibernate validates against the Flyway-applied
        // schema instead of creating its own from the entity model.
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Flyway: run V1 from scratch into the freshly-created H2 DB. No
        // baseline-on-migrate — there are no pre-existing tables to baseline.
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.locations=classpath:db/migration",
        // Quiet the SQL log — this test has predictable output and doesn't
        // benefit from the per-statement dump.
        "spring.jpa.show-sql=false",
})
@EntityScan(basePackages = "com.xai.trident.model")
@EnableJpaRepositories(basePackages = "com.xai.trident.repository")
public class SchemaMigrationTest {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private FaxLogRepository faxLogRepository;

    @Autowired
    private FaxMetadataRepository faxMetadataRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * The implicit assertion: the Spring context loaded. With
     * {@code ddl-auto=validate}, Hibernate's schema validator runs at startup
     * and refuses to bring the context up if any {@code @Entity} column doesn't
     * exist (or has the wrong type / nullability) in the Flyway-applied
     * schema. So if this test runs at all — including the @Autowired
     * injections above — the schema is in lockstep with the entities.
     *
     * <p>The body just touches each repository so the autowire isn't an
     * unused-warning lint and to confirm the JPA repository proxies are
     * also reachable.
     */
    @Test
    public void context_loads_means_flyway_applied_and_hibernate_validate_passed() {
        assertNotNull(contactRepository, "ContactRepository should be wired");
        assertNotNull(faxLogRepository, "FaxLogRepository should be wired");
        assertNotNull(faxMetadataRepository, "FaxMetadataRepository should be wired");
        assertNotNull(userRepository, "UserRepository should be wired");

        // Confirm the four tables are actually queryable (catches the case
        // where Flyway created them but JPA-side mapping reads through to a
        // missing alias / view / etc.).
        contactRepository.count();
        faxLogRepository.count();
        faxMetadataRepository.count();
        userRepository.count();
    }

    /**
     * Persists and reads back one row per entity, with the FK chain wired
     * correctly: FaxMetadata → FaxLog → Contact. Each save crossing the entity
     * boundary into the DB is a hand-shake between Hibernate's INSERT and the
     * Flyway-created table; if column ordering, types, nullability, or the FK
     * constraints don't match, this fails.
     *
     * <p>{@code @DataJpaTest} wraps each test in a transaction that rolls back
     * on completion, so no rows persist into the next test.
     */
    @Test
    public void each_entity_round_trips() {
        // Contact (root of the FK chain).
        Contact contact = new Contact("Round-Trip Test", "+15555550100");
        contact.setEmail("round@trip.test");
        contact.setOrganization("Test Co.");
        Contact savedContact = contactRepository.save(contact);
        assertNotNull(savedContact.getId(), "Contact should get a generated id");

        Optional<Contact> readBack = contactRepository.findByFaxNumber("+15555550100");
        assertTrue(readBack.isPresent(), "Contact should be readable by faxNumber");
        assertEquals("Round-Trip Test", readBack.get().getName());

        // FaxLog (FK → contacts, nullable contact_id by design — see Contact.java).
        FaxLog log = new FaxLog("fax_smt_1", "sending", "+15555550100", "test.pdf");
        log.setContact(savedContact);
        FaxLog savedLog = faxLogRepository.save(log);
        assertNotNull(savedLog.getId(), "FaxLog should get a generated id");
        assertEquals(1L, faxLogRepository.countByFaxNumber("+15555550100"));

        // FaxMetadata (FK → fax_logs, NOT NULL).
        FaxMetadata metadata = new FaxMetadata("test.pdf", 1, "PDF", 1024L);
        metadata.setFaxLog(savedLog);
        FaxMetadata savedMetadata = faxMetadataRepository.save(metadata);
        assertNotNull(savedMetadata.getId(), "FaxMetadata should get a generated id");

        // User (no FK; PK is the username String, not a generated id).
        User user = new User();
        user.setUsername("schemamig-user");
        user.setPassword("not-a-real-hash");
        user.setRoles("USER");
        userRepository.save(user);
        assertTrue(userRepository.findByUsername("schemamig-user").isPresent(),
                "User should be readable by username");
    }
}
