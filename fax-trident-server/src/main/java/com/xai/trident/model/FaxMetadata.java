package com.xai.trident.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

@Entity
@Table(name = "fax_metadata", indexes = {
    @Index(name = "idx_fax_log_id", columnList = "fax_log_id"),
    @Index(name = "idx_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
public class FaxMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "File name cannot be blank")
    @Column(nullable = false)
    private String fileName;

    @Positive(message = "Page count must be positive")
    @Column(nullable = false)
    private int pageCount;

    @Pattern(regexp = "^(PDF|TIFF|JPG|PNG)$", message = "Invalid file type")
    @Column
    private String fileType; // e.g., "PDF", "TIFF" with validation

    @PositiveOrZero(message = "File size must be non-negative")
    @Column
    private Long fileSize; // Size in bytes with validation

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fax_log_id", nullable = false)
    private FaxLog faxLog; // Required link to FaxLog for tracking

    @Column
    private String createdBy; // Audit trail for creator

    // Custom constructor for required fields
    public FaxMetadata(String fileName, int pageCount) {
        this.fileName = fileName;
        this.pageCount = pageCount;
    }

    // Constructor with additional fields
    public FaxMetadata(String fileName, int pageCount, String fileType, Long fileSize) {
        this(fileName, pageCount);
        this.fileType = fileType;
        this.fileSize = fileSize;
    }

    @PrePersist
    public void prePersist() {
        String username = SecurityContextHolder.getContext().getAuthentication() != null ?
            SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        this.createdBy = username;
    }
}
