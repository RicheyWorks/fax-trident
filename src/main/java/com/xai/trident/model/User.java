package com.xai.trident.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Application user record backing Spring Security's {@code UserDetailsService}.
 *
 * <p>Previously lived as a static inner class of {@code SecurityConfig}; moved
 * out as part of audit finding 2.15 so the entity sits with the other JPA
 * models. Hibernate still picks it up via the {@code @SpringBootApplication}
 * scan rooted at {@code com.xai.trident}.
 *
 * <p>{@code roles} is a comma-separated list of role names (without the
 * {@code ROLE_} prefix) — e.g. {@code "USER,ADMIN"}. This shape is preserved
 * verbatim from the original inner-class definition to keep migration mechanical;
 * the audit's broader recommendation is to switch to a join table eventually.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private String username;

    private String password;

    private String roles;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }
}
