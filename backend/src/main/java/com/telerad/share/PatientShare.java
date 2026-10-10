package com.telerad.share;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientShare {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** Primary study this share was created for (viewer landing study). */
    @Column(name = "main_study_id")
    private UUID mainStudyId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @Column(name = "consent_by", nullable = false)
    private UUID consentBy;

    /** SHA-256 hex of the opaque link token (high-entropy; used for lookup). */
    @Column(name = "link_token_lookup", nullable = false, length = 64)
    private String linkTokenLookup;

    @Column(name = "access_code_hash", nullable = false, length = 100)
    private String accessCodeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "code_version", nullable = false)
    @Builder.Default
    private int codeVersion = 1;

    @Column(name = "include_reports", nullable = false)
    @Builder.Default
    private boolean includeReports = true;

    @Column(name = "allow_dicom_download", nullable = false)
    @Builder.Default
    private boolean allowDicomDownload = false;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }
}
