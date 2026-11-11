package com.telerad.annotation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/studies/{studyId}")
@RequiredArgsConstructor
public class StudyAnnotationController {
    private final StudyAnnotationService service;

    @GetMapping("/measurements")
    public List<StudyAnnotationService.MeasurementDto> measurements(@PathVariable UUID studyId) {
        return service.listMeasurements(studyId);
    }

    @PutMapping("/measurements/{measurementId}")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public StudyAnnotationService.MeasurementDto saveMeasurement(@PathVariable UUID studyId,
                                                                 @PathVariable UUID measurementId,
                                                                 @RequestBody StudyAnnotationService.MeasurementRequest request) {
        return service.upsertMeasurement(studyId, measurementId, request);
    }

    @DeleteMapping("/measurements/{measurementId}")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public ResponseEntity<Void> deleteMeasurement(@PathVariable UUID studyId, @PathVariable UUID measurementId) {
        service.deleteMeasurement(studyId, measurementId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/key-images")
    public List<StudyAnnotationService.KeyImageDto> keyImages(@PathVariable UUID studyId) {
        return service.listKeyImages(studyId);
    }

    @PostMapping("/key-images")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public StudyAnnotationService.KeyImageDto addKeyImage(@PathVariable UUID studyId,
                                                          @RequestBody StudyAnnotationService.KeyImageRequest request) {
        return service.addKeyImage(studyId, request);
    }

    @DeleteMapping("/key-images/{keyImageId}")
    @PreAuthorize("hasAnyRole('RADIOLOGIST','ADMIN')")
    public ResponseEntity<Void> deleteKeyImage(@PathVariable UUID studyId, @PathVariable UUID keyImageId) {
        service.deleteKeyImage(studyId, keyImageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/key-images/{keyImageId}/image")
    public ResponseEntity<byte[]> keyImage(@PathVariable UUID studyId, @PathVariable UUID keyImageId) {
        StudyAnnotationService.ReportImage img = service.keyImageSnapshot(studyId, keyImageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(img.jpeg());
    }
}
