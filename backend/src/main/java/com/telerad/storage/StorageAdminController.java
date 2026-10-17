package com.telerad.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/storage")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class StorageAdminController {
    private final GzipVariantService gzipVariantService;

    /** Create gzip copies for objects stored before they existed. Call repeatedly until remaining = 0. */
    @PostMapping("/gzip-backfill")
    public Map<String, Object> gzipBackfill(@RequestParam(defaultValue = "1000") int limit,
                                            @RequestParam(defaultValue = "4") int threads) throws InterruptedException {
        return gzipVariantService.backfill(Math.max(1, Math.min(limit, 5000)), threads);
    }
}
