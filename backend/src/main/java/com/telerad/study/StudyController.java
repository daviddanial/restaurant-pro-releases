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

    /** Earlier studies of the same patient for side-by-side comparison (merged from upstream). */
    @GetMapping("/api/studies/{id}/priors")
    public java.util.List<StudyService.StudyListItem> priors(@PathVariable UUID id) {
        return studyService.priors(id);
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

    /** Physician order (Feya): create the study before DICOM exists, with a recommended radiologist. */
    @PostMapping("/api/hospitals/{hospitalId}/studies/order")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public StudyService.StudyListItem order(@PathVariable UUID hospitalId,
                                            @RequestBody StudyService.OrderRequest body) {
        if (body == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "body is required");
        }
        return studyService.order(hospitalId, body);
    }

    /** Nurse compliance: the ordered study has been performed. */
    @PostMapping("/api/studies/{id}/comply")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public StudyService.StudyListItem comply(@PathVariable UUID id) {
        return studyService.comply(id);
    }

    /** Nurse feedback (e.g. "recommended radiologist not available") back to the physician. */
    @PostMapping("/api/studies/{id}/feedback")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public StudyService.StudyListItem feedback(@PathVariable UUID id,
                                               @RequestBody StudyService.FeedbackRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "message is required");
        }
        return studyService.feedback(id, body.message());
    }

    /** Hybrid availability: null clears the override (back to auto-from-workload). */
    @PostMapping("/api/radiologists/{id}/availability")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','HOSPITAL_USER','ADMIN')")
    public StudyService.RadiologistOption availability(@PathVariable UUID id,
                                                       @RequestBody(required = false) StudyService.AvailabilityRequest body) {
        return studyService.setAvailability(id, body == null ? null : body.available());
    }

    /** Look a study up by the external (Feya) order id — correlation without shared keys. */
    @GetMapping("/api/studies/by-order/{externalOrderId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public StudyService.StudyDetail byOrder(@PathVariable String externalOrderId) {
        Study study = studyService.findByExternalOrderId(externalOrderId);
        return studyService.getStudy(study.getId());
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
