package com.xai.trident.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

@Entity
@Table(name = "fax_logs", indexes = {
    @Index(name = "idx_fax_id", columnList = "faxId"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_fax_number", columnList = "faxNumber")
})
@Data
@NoArgsConstructor
public class FaxLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Fax ID cannot be blank")
    // FIX: removed unique = true — FaxEngineService writes multiple log rows per
    // fax (e.g. "sending" then "sent"), so uniqueness on faxId is wrong here.
    @Column(nullable = false)
    private String faxId;

    @NotBlank(message = "Status cannot be blank")
    @Column(nullable = false)
    private String status;

    @Column
    private String faxNumber;

    @Column
    private String filePath;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    // FIX: changed from default VARCHAR(255) to TEXT so full exception messages
    // don't overflow the column and cause DataIntegrityViolationException.
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private String createdBy;

    public FaxLog(String faxId, String status, String faxNumber, String filePath) {
        this.faxId = faxId;
        this.status = status;
        this.faxNumber = faxNumber;
        this.filePath = filePath;
    }

    public FaxLog(String faxId, String status, String faxNumber, String filePath, String errorMessage) {
        this(faxId, status, faxNumber, filePath);
        this.errorMessage = errorMessage;
    }

    @PrePersist
    public void prePersist() {
        String username = SecurityContextHolder.getContext().getAuthentication() != null ?
            SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        this.createdBy = username;
    }
}
