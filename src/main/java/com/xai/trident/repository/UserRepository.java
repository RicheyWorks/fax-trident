package com.xai.trident.repository;

import com.xai.trident.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for {@link User}. Extracted from {@code SecurityConfig}
 * as part of audit finding 2.15 — the entity and its repository now live in
 * the conventional {@code model/} and {@code repository/} packages instead of
 * as nested types of a {@code @Configuration} class.
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
}
