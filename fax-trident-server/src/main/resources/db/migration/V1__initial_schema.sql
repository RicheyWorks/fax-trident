-- V1: initial schema baseline (2026-05-17).
--
-- This migration captures the schema as Hibernate's auto-generated DDL leaves it
-- at audit close. It is the boundary between "Hibernate owns the schema via
-- ddl-auto" and "Flyway owns the schema, Hibernate only validates."
--
-- Identifier casing follows what Hibernate actually emits today: camelCase
-- preserved verbatim except where @JoinColumn explicitly says snake_case
-- (contact_id, fax_log_id). PostgreSQL folds unquoted identifiers to lowercase
-- internally, so the on-disk column names are lowercase regardless; Hibernate's
-- generated SQL is also unquoted, so queries match on both Postgres (folded) and
-- H2 (case-insensitive). Don't quote these identifiers — that would break the
-- match with existing prod schemas where Hibernate created them unquoted.
--
-- Operator note for existing prod databases:
-- spring.flyway.baseline-on-migrate=true is set in application.yml. On first
-- startup against an existing database (one with tables but no
-- flyway_schema_history), Flyway inserts a baseline row for V1 and skips this
-- file. Subsequent V2+ migrations run normally. New deployments (fresh DB) run
-- V1 in full.

-- ====================================================================
-- contacts
-- ====================================================================
CREATE TABLE contacts (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    faxNumber   VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    organization VARCHAR(255),
    createdAt   TIMESTAMP,
    updatedAt   TIMESTAMP,
    createdBy   VARCHAR(255),
    updatedBy   VARCHAR(255),
    CONSTRAINT uk_contacts_fax_number UNIQUE (faxNumber)
);

CREATE INDEX idx_contact_fax_number ON contacts (faxNumber);
CREATE INDEX idx_name               ON contacts (name);

-- ====================================================================
-- fax_logs
--   contact_id is intentionally nullable: deleting a Contact must not
--   destroy its audit trail (see Contact.java for the rationale on why
--   cascade=ALL / orphanRemoval were stripped — audit 2.11).
-- ====================================================================
CREATE TABLE fax_logs (
    id           BIGSERIAL    PRIMARY KEY,
    faxId        VARCHAR(255) NOT NULL,
    status       VARCHAR(255) NOT NULL,
    faxNumber    VARCHAR(255),
    filePath     VARCHAR(255),
    timestamp    TIMESTAMP,
    contact_id   BIGINT,
    errorMessage TEXT,
    createdBy    VARCHAR(255),
    CONSTRAINT fk_fax_logs_contact_id
        FOREIGN KEY (contact_id) REFERENCES contacts (id)
);

CREATE INDEX idx_fax_id     ON fax_logs (faxId);
CREATE INDEX idx_status     ON fax_logs (status);
CREATE INDEX idx_timestamp  ON fax_logs (timestamp);
CREATE INDEX idx_fax_number ON fax_logs (faxNumber);

-- ====================================================================
-- fax_metadata
--   fax_log_id is NOT NULL: every metadata row must reference a fax_log
--   (FaxMetadata.faxLog has nullable = false).
-- ====================================================================
CREATE TABLE fax_metadata (
    id         BIGSERIAL    PRIMARY KEY,
    fileName   VARCHAR(255) NOT NULL,
    pageCount  INTEGER      NOT NULL,
    fileType   VARCHAR(255),
    fileSize   BIGINT,
    createdAt  TIMESTAMP,
    fax_log_id BIGINT       NOT NULL,
    createdBy  VARCHAR(255),
    CONSTRAINT fk_fax_metadata_fax_log_id
        FOREIGN KEY (fax_log_id) REFERENCES fax_logs (id)
);

CREATE INDEX idx_fax_log_id  ON fax_metadata (fax_log_id);
CREATE INDEX idx_created_at  ON fax_metadata (createdAt);

-- ====================================================================
-- users
--   Backs Spring Security's UserDetailsService (see SecurityConfig +
--   model/User.java). roles is a comma-separated list of role names
--   without the ROLE_ prefix (e.g. "USER,ADMIN") — audit notes a future
--   migration to a join table is preferable.
-- ====================================================================
CREATE TABLE users (
    username VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255),
    roles    VARCHAR(255)
);
