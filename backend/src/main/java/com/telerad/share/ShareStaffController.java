package com.telerad.share;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
public class ShareStaffController {
    private final ShareService shareService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public ShareService.CreateResult create(@RequestBody ShareService.CreateRequest body, HttpServletRequest req) {
        return shareService.create(body, req);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public List<ShareService.ShareListItem> list(@RequestParam(required = false) UUID patientId) {
        return shareService.list(patientId);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public ShareService.ShareListItem revoke(@PathVariable UUID id, HttpServletRequest req) {
        return shareService.revoke(id, req);
    }

    @PostMapping("/{id}/regenerate-code")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public Map<String, Object> regenerate(@PathVariable UUID id, HttpServletRequest req) {
        return shareService.regenerateCode(id, req);
    }
}
