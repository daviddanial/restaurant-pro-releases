package com.telerad.share;

import com.telerad.audit.AuditService;
import com.telerad.export.ExportScope;
import com.telerad.export.ExportService;
import com.telerad.study.StudyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SharePublicController {
    private final ShareService shareService;
    private final ExportService exportService;
    private final AuditService auditService;

    public record UnlockRequest(String accessCode) {}

    @GetMapping("/api/public/shares/{linkToken}")
    public ShareService.PublicMeta meta(
            @PathVariable String linkToken,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.publicMeta(linkToken);
    }

    @PostMapping("/api/public/shares/{linkToken}/unlock")
    public ShareService.UnlockResult unlock(
            @PathVariable String linkToken,
            @RequestBody UnlockRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.unlock(linkToken, body == null ? null : body.accessCode(), request);
    }

    @GetMapping("/api/public/shares/me")
    @PreAuthorize("hasRole('SHARE')")
    public ShareService.ShareSessionInfo me(HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.mySession();
    }

    @GetMapping("/api/public/shares/me/studies")
    @PreAuthorize("hasRole('SHARE')")
    public List<ShareService.ShareStudyItem> studies(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.listMyStudies(request);
    }

    @GetMapping("/api/public/shares/me/studies/{studyId}")
    @PreAuthorize("hasRole('SHARE')")
    public StudyService.StudyDetail study(
            @PathVariable UUID studyId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.getMyViewerStudy(studyId, request);
    }

    @GetMapping("/api/public/shares/me/studies/{studyId}/priors")
    @PreAuthorize("hasRole('SHARE')")
    public List<StudyService.StudyListItem> priors(
            @PathVariable UUID studyId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        return shareService.listMyFrozenPriors(studyId, request);
    }

    @GetMapping("/api/public/shares/me/studies/{studyId}/report.pdf")
    @PreAuthorize("hasRole('SHARE')")
    public ResponseEntity<byte[]> reportPdf(
            @PathVariable UUID studyId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        byte[] pdf = shareService.downloadSignedPdf(studyId, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/api/public/shares/me/studies/{studyId}/export")
    @PreAuthorize("hasRole('SHARE')")
    public ResponseEntity<?> exportDicom(
            @PathVariable UUID studyId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        SharePrincipal sp = SharePrincipal.current();
        if (!sp.isAllowDicomDownload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "DICOM download not enabled"));
        }
        shareService.assertShareCanViewStudy(studyId);
        var job = exportService.startForShare(studyId, new ExportService.StartRequest(ExportScope.STUDY, null, null), sp);
        auditService.record(sp.getTenantId(), null, "SHARE_DOWNLOAD_DICOM", "Study", studyId.toString(),
                "job=" + job.id(), ShareService.clientIp(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/api/public/shares/me/exports/{jobId}")
    @PreAuthorize("hasRole('SHARE')")
    public ExportService.ExportJobDto exportStatus(@PathVariable UUID jobId) {
        return exportService.statusForShare(jobId, SharePrincipal.current());
    }

    @GetMapping("/api/public/shares/me/exports/{jobId}/download")
    @PreAuthorize("hasRole('SHARE')")
    public ResponseEntity<byte[]> exportDownload(@PathVariable UUID jobId, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        byte[] zip = exportService.downloadForShare(jobId, SharePrincipal.current());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"share-export.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }
}
