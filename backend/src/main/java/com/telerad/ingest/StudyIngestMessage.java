package com.telerad.ingest;

import java.util.UUID;

public record StudyIngestMessage(
        UUID tenantId,
        String storageKey,
        String studyInstanceUidHint,
        String source,
        String callingAe
) {}
