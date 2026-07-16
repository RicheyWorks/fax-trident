package com.xai.trident.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
// Index names live in a single global namespace per schema (Hibernate/H2/Postgres
// all enforce this), so the index on `contacts.faxNumber` and the index on
// `fax_logs.faxNumber` (declared in FaxLog) cannot share a name. Until this was
// renamed, schema creation would silently fail on the second CREATE INDEX and
// `fax_logs.faxNumber` would be unindexed in production — a real performance bug
// since FaxLogRepository.findByFaxNumber and countByFaxNumber are hot paths.
// See AUDIT.md §2.22.
@Table(name = "contacts", indexes = {
    @Index(name = "idx_contact_fax_number", columnList = "faxNumber"),
    @Index(name = "idx_name", columnList = "name")
})
@Data // Lombok: getters, setters, toString, equals, hashCode
@NoArgsConstructor // Lombok: no-args constructor
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name cannot be blank")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "Fax number cannot be blank")
    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Invalid fax number format")
    @Column(nullable = false, unique = true)
    private String faxNumber;

    @Email(message = "Invalid email format")
    @Column
    private String email; // Optional contact info with validation

    @Column
    private String organization; // Optional company name

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column
    private String createdBy; // Audit trail for creator

    @Column
    private String updatedBy; // Audit trail for last updater

    // One-to-Many relationship with FaxLog.
    //
    // Intentionally NO `cascade = CascadeType.ALL` and NO `orphanRemoval = true`.
    // FaxLog rows are an audit trail and must outlive the Contact they reference:
    //   - cascade=ALL meant `contactRepository.delete(c)` deleted every FaxLog
    //     that pointed at it.
    //   - orphanRemoval meant `contact.getFaxLogs().remove(log)` (just removing
    //     it from the in-memory collection) deleted the row from the DB.
    // Both behaviors silently destroyed compliance/audit data. FaxLogs are now
    // managed explicitly via FaxLogRepository. If you ever need to delete a
    // Contact, decide separately what should happen to its logs (typically:
    // null-out `contact_id` and keep the row).
    @OneToMany(mappedBy = "contact", fetch = FetchType.LAZY)
    private List<FaxLog> faxLogs = new ArrayList<>();

    // Custom constructor for required fields
    public Contact(String name, String faxNumber) {
        this.name = name;
        this.faxNumber = faxNumber;
    }

    // Helper method to add a fax log
    public void addFaxLog(FaxLog faxLog) {
        faxLogs.add(faxLog);
        faxLog.setContact(this);
    }

    @PrePersist
    public void prePersist() {
        String username = SecurityContextHolder.getContext().getAuthentication() != null ?
            SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        this.createdBy = username;
        this.updatedBy = username;
    }

    @PreUpdate
    public void preUpdate() {
        String username = SecurityContextHolder.getContext().getAuthentication() != null ?
            SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        this.updatedBy = username;
    }
}
