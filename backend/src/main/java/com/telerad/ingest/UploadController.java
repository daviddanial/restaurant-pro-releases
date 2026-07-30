package com.telerad.ingest;

import com.telerad.common.StudyPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/hospitals/{hospitalId}/studies")
@RequiredArgsConstructor
public class UploadController {
    private final UploadService uploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public Map<String, Object> upload(
            @PathVariable UUID hospitalId,
            @RequestPart("files") MultipartFile[] files,
            @RequestParam(value = "priority", required = false, defaultValue = "ROUTINE") StudyPriority priority) {
        if (files == null || files.length == 0) {
            return Map.of("error", "No files provided");
        }
        return uploadService.upload(hospitalId, files, priority);
    }
}
