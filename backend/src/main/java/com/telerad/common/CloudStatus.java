package com.telerad.common;

/** Where a study's image files are, as seen from the cloud (KB note 13). */
public enum CloudStatus {
    /** Image files are in cloud object storage. */
    AVAILABLE,
    /** Only on the hospital server; the cloud has the index (study, series, instance rows). */
    EDGE_ONLY,
    /** The hospital server was asked to upload the image files. */
    REQUESTED,
    /** Retention reached; removed once the hospital server confirms it still holds the study. */
    PENDING_DELETE
}
