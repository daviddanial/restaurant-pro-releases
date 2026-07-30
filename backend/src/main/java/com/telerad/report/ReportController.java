package com.telerad.report;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reportService;

    @PutMapping("/api/studies/{id}/report")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public ReportService.ReportDto saveDraft(@PathVariable("id") UUID studyId,
                                             @RequestBody ReportService.ReportRequest request) {
        return reportService.saveDraft(studyId, request);
    }

    @PostMapping("/api/studies/{id}/report/sign")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public ReportService.ReportDto sign(@PathVariable("id") UUID studyId) {
        return reportService.sign(studyId);
    }

    @GetMapping("/api/studies/{id}/report")
    public ReportService.ReportDto get(@PathVariable("id") UUID studyId) {
        return reportService.getByStudy(studyId);
    }

    @GetMapping("/api/hospitals/{hospitalId}/reports")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public List<ReportService.HospitalReportItem> hospitalReports(@PathVariable UUID hospitalId) {
        return reportService.hospitalReports(hospitalId);
    }

    @GetMapping("/api/reports/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        byte[] pdf = reportService.pdfBytes(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
