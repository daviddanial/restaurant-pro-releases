package com.telerad.export;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ExportController {
    private final ExportService exportService;

    @PostMapping("/api/studies/{id}/export")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN','HOSPITAL_USER')")
    public ResponseEntity<ExportService.ExportJobDto> start(
            @PathVariable("id") UUID studyId,
            @RequestBody ExportService.StartRequest body) {
        ExportService.ExportJobDto job = exportService.start(studyId, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/api/exports/{jobId}")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN','HOSPITAL_USER')")
    public ExportService.ExportJobDto status(@PathVariable UUID jobId) {
        return exportService.status(jobId);
    }

    @GetMapping("/api/exports/{jobId}/download")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN','HOSPITAL_USER')")
    public ResponseEntity<byte[]> download(@PathVariable UUID jobId) {
        byte[] zip = exportService.download(jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export-" + jobId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }
}
