package com.telerad.report;

import com.telerad.annotation.StudyAnnotationService;
import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.*;
import com.telerad.notify.NotificationService;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Patient;
import com.telerad.study.PatientRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {
    private final ReportRepository reportRepository;
    private final StudyRepository studyRepository;
    private final PatientRepository patientRepository;
    private final TenantRepository tenantRepository;
    private final ObjectStorageService storage;
    private final AuditService auditService;
    private final ReportDeliveryService reportDeliveryService;
    private final NotificationService notificationService;
    private final StudyAnnotationService annotationService;

    public record ReportRequest(String findings, String impression, Boolean critical) {}

    public record ReportDto(UUID id, UUID studyId, ReportStatus status, String findings, String impression,
                            boolean critical, Instant signedAt, UUID signerId, boolean hasPdf) {}

    public record HospitalReportItem(UUID reportId, UUID studyId, String studyUid, String accession,
                                     String patientName, String modality, Instant signedAt,
                                     boolean critical, String impression) {}

    @Transactional
    public ReportDto saveDraft(UUID studyId, ReportRequest request) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireEditableStudy(studyId, user);
        Report report = reportRepository.findByStudyId(studyId).orElseGet(() -> Report.builder()
                .studyId(studyId)
                .authorId(user.getId())
                .build());
        if (report.getStatus() == ReportStatus.SIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report already signed");
        }
        report.setFindings(request.findings());
        report.setImpression(request.impression());
        if (request.critical() != null) report.setCritical(request.critical());
        report.setAuthorId(user.getId());
        report.setVersion(report.getVersion() + 1);
        reportRepository.save(report);
        if (study.getStatus() == StudyStatus.READY) {
            study.setStatus(StudyStatus.IN_PROGRESS);
            study.setAssignedTo(user.getId());
            studyRepository.save(study);
        }
        return toDto(report);
    }

    @Transactional
    public ReportDto sign(UUID studyId) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireEditableStudy(studyId, user);
        Report report = reportRepository.findByStudyId(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No draft report"));
        if (report.getStatus() == ReportStatus.SIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already signed");
        }
        if (report.getImpression() == null || report.getImpression().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impression required to sign");
        }
        report.setStatus(ReportStatus.SIGNED);
        report.setSignerId(user.getId());
        report.setSignedAt(Instant.now());

        byte[] pdf = renderPdf(study, report, user.getFullName());
        String pdfKey = study.getTenantId() + "/" + study.getStudyInstanceUid() + "/reports/" + report.getId() + ".pdf";
        storage.put(pdfKey, pdf, "application/pdf");
        report.setPdfStorageKey(pdfKey);
        reportRepository.save(report);

        study.setStatus(StudyStatus.REPORTED);
        study.setReportedAt(Instant.now());
        studyRepository.save(study);

        reportDeliveryService.deliverPortal(report, pdfKey);

        String fhirKey = study.getTenantId() + "/" + study.getStudyInstanceUid() + "/reports/" + report.getId() + ".fhir.json";
        String fhirJson = buildFhirJson(study, report);
        storage.put(fhirKey, fhirJson.getBytes(StandardCharsets.UTF_8), "application/fhir+json");
        reportDeliveryService.deliverFhirStored(report, fhirKey);
        reportDeliveryService.deliverFhirWebhook(study, report, fhirJson);
        reportDeliveryService.deliverEmailStub(study, report, pdfKey);

        study.setStatus(StudyStatus.DELIVERED);
        study.setDeliveredAt(Instant.now());
        studyRepository.save(study);

        String impression = report.getImpression();
        boolean critical = report.isCritical();
        runAfterCommit(() -> notificationService.notifyReportSigned(study, impression, critical, fhirJson, pdfKey));

        auditService.record(study.getTenantId(), user.getId(), "SIGN_REPORT", "Report",
                report.getId().toString(), "critical=" + report.isCritical(), null);
        return toDto(report);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Transactional(readOnly = true)
    public ReportDto getByStudy(UUID studyId) {
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertCanView(UserPrincipal.current(), study);
        return reportRepository.findByStudyId(studyId).map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report"));
    }

    @Transactional(readOnly = true)
    public List<HospitalReportItem> hospitalReports(UUID hospitalId) {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() != Role.ADMIN && !hospitalId.equals(user.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<Study> studies = studyRepository.findByTenantIdOrderByCreatedAtDesc(hospitalId);
        List<HospitalReportItem> items = new ArrayList<>();
        for (Study s : studies) {
            reportRepository.findByStudyId(s.getId()).ifPresent(r -> {
                if (r.getStatus() == ReportStatus.SIGNED) {
                    String patientName = s.getPatientId() == null ? null :
                            patientRepository.findById(s.getPatientId()).map(Patient::getPatientName).orElse(null);
                    items.add(new HospitalReportItem(r.getId(), s.getId(), s.getStudyInstanceUid(),
                            s.getAccessionNumber(), patientName, s.getModality(), r.getSignedAt(),
                            r.isCritical(), r.getImpression()));
                }
            });
        }
        return items;
    }

    @Transactional(readOnly = true)
    public byte[] pdfBytes(UUID reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Study study = studyRepository.findById(report.getStudyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertCanView(UserPrincipal.current(), study);
        if (report.getPdfStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF not available");
        }
        auditService.record(study.getTenantId(), UserPrincipal.current().getId(), "DOWNLOAD_PDF",
                "Report", reportId.toString(), null, null);
        return storage.getBytes(report.getPdfStorageKey());
    }

    private Study requireEditableStudy(UUID studyId, UserPrincipal user) {
        if (user.getRole() != Role.RADIOLOGIST && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (study.getStatus() == StudyStatus.REPORTED || study.getStatus() == StudyStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Study already reported");
        }
        if (study.getAssignedTo() != null && !study.getAssignedTo().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Study claimed by another user");
        }
        return study;
    }

    private void assertCanView(UserPrincipal user, Study study) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private ReportDto toDto(Report r) {
        return new ReportDto(r.getId(), r.getStudyId(), r.getStatus(), r.getFindings(), r.getImpression(),
                r.isCritical(), r.getSignedAt(), r.getSignerId(), r.getPdfStorageKey() != null);
    }

    private byte[] renderPdf(Study study, Report report, String signerName) {
        String hospital = tenantRepository.findById(study.getTenantId()).map(t -> t.getName()).orElse("Hospital");
        String patient = study.getPatientId() == null ? "N/A" :
                patientRepository.findById(study.getPatientId()).map(Patient::getPatientName).orElse("N/A");
        String html = """
                <html><head><style>
                body{font-family:sans-serif;margin:40px;color:#111}
                h1{font-size:20px;border-bottom:2px solid #0b3d5c;padding-bottom:8px}
                .meta{margin:16px 0;font-size:12px;color:#333}
                .section{margin-top:24px}
                .section p{white-space:pre-wrap}
                .label{font-weight:bold;color:#0b3d5c}
                .critical{color:#b00020;font-weight:bold}
                </style></head><body>
                <h1>Radiology Report</h1>
                <div class="meta">
                  <div><span class="label">Hospital:</span> %s</div>
                  <div><span class="label">Patient:</span> %s</div>
                  <div><span class="label">Accession:</span> %s</div>
                  <div><span class="label">Modality:</span> %s</div>
                  <div><span class="label">Study UID:</span> %s</div>
                  <div><span class="label">Signed by:</span> %s</div>
                  <div><span class="label">Signed at:</span> %s</div>
                  %s
                </div>
                <div class="section"><div class="label">Findings</div><p>%s</p></div>
                <div class="section"><div class="label">Impression</div><p>%s</p></div>
                %s
                </body></html>
                """.formatted(
                esc(hospital), esc(patient), esc(study.getAccessionNumber()), esc(study.getModality()),
                esc(study.getStudyInstanceUid()), esc(signerName), report.getSignedAt(),
                report.isCritical() ? "<div class=\"critical\">CRITICAL RESULT</div>" : "",
                esc(report.getFindings()), esc(report.getImpression()),
                keyImagesHtml(study)
        );
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF generation failed: " + e.getMessage());
        }
    }

    /** "Key images" section: snapshots saved in the viewer, two per row, with their location as caption. */
    private String keyImagesHtml(Study study) {
        List<StudyAnnotationService.ReportImage> images = annotationService.reportImages(study.getId());
        if (images.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div class=\"section\"><div class=\"label\">Key images</div>"
                + "<table style=\"width:100%;border-collapse:collapse;margin-top:8px\">");
        for (int i = 0; i < images.size(); i++) {
            if (i % 2 == 0) sb.append("<tr>");
            StudyAnnotationService.ReportImage img = images.get(i);
            sb.append("<td style=\"width:50%;padding:6px;vertical-align:top;text-align:center\">")
              .append("<img style=\"width:240px\" src=\"data:").append(img.contentType()).append(";base64,")
              .append(Base64.getEncoder().encodeToString(img.jpeg())).append("\" />")
              .append("<div style=\"font-size:10px;color:#333;margin-top:4px\">")
              .append(esc(img.location() != null ? img.location() : "Key image " + (i + 1))).append("</div></td>");
            if (i % 2 == 1 || i == images.size() - 1) sb.append("</tr>");
        }
        return sb.append("</table></div>").toString();
    }

    private String buildFhirJson(Study study, Report report) {
        return """
                {
                  "resourceType": "DiagnosticReport",
                  "status": "final",
                  "code": { "text": "Radiology Report" },
                  "effectiveDateTime": "%s",
                  "conclusion": %s,
                  "presentedForm": [{ "contentType": "application/pdf", "url": "%s" }]
                }
                """.formatted(report.getSignedAt(), jsonString(report.getImpression()), report.getPdfStorageKey());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
