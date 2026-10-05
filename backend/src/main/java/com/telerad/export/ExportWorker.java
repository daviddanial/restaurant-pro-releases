package com.telerad.export;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExportWorker {
    private final ExportService exportService;

    @Async
    public void run(UUID jobId) {
        exportService.process(jobId);
    }
}
