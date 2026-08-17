package com.telerad.study;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class StudyController {
    private final StudyService studyService;

    @GetMapping("/api/worklist")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public List<StudyService.StudyListItem> worklist(
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String status) {
        return studyService.worklist(modality, priority, status);
    }

    @GetMapping("/api/hospitals/{hospitalId}/studies")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public List<StudyService.StudyListItem> hospitalStudies(@PathVariable UUID hospitalId) {
        return studyService.hospitalStudies(hospitalId);
    }

    @GetMapping("/api/studies/{id}")
    public StudyService.StudyDetail get(@PathVariable UUID id) {
        return studyService.getStudy(id);
    }

    @PostMapping("/api/studies/{id}/claim")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public StudyService.StudyListItem claim(@PathVariable UUID id) {
        return studyService.claim(id);
    }

    @GetMapping("/api/radiologists")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public List<StudyService.RadiologistOption> radiologists() {
        return studyService.radiologists();
    }

    @PostMapping("/api/studies/{id}/assign")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public StudyService.StudyListItem assign(@PathVariable UUID id, @RequestBody AssignRequest body) {
        if (body == null || body.radiologistId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "radiologistId is required");
        }
        return studyService.assign(id, body.radiologistId());
    }

    public record AssignRequest(UUID radiologistId) {}
}
