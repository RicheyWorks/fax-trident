package com.xai.trident.repository;

import com.xai.trident.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice tests for {@link UserRepository}. Smallest repo in the project —
 * one custom finder ({@code findByUsername}) on top of the standard
 * {@code JpaRepository} CRUD. Used here as the pattern reference for the
 * other repository slice tests (Contact / FaxLog / FaxMetadata).
 *
 * <p>Test boot mirrors {@code SchemaMigrationTest}: H2 in PostgreSQL mode,
 * Flyway runs {@code V1__initial_schema.sql} into a fresh DB, Hibernate
 * validates the entity model against the migrated schema. The repo bean
 * is provided by {@code @EnableJpaRepositories} pointing at the
 * production package.
 *
 * <p>Each {@code @Test} method is wrapped in a transaction by
 * {@code @DataJpaTest} and rolled back on completion, so tests don't
 * leak data into each other.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:userrepotest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void save_thenFindByUsername_returnsPersistedUser() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("{bcrypt}$2a$10$irrelevant");
        user.setRoles("USER,ADMIN");
        userRepository.save(user);

        Optional<User> found = userRepository.findByUsername("alice");

        assertTrue(found.isPresent(), "saved user should be findable");
        assertEquals("alice", found.get().getUsername());
        assertEquals("USER,ADMIN", found.get().getRoles());
    }

    @Test
    void findByUsername_unknown_returnsEmpty() {
        Optional<User> found = userRepository.findByUsername("no-such-user");
        assertFalse(found.isPresent(), "lookup of a non-existent username must be Optional.empty, not throw");
    }
}
