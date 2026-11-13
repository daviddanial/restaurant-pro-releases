package com.telerad.common;

/** Which studies a facility's hospital server uploads to the cloud. */
public enum UploadPolicy {
    /** Every study (previous behaviour). */
    ALL,
    /** Only the index; image files when requested by the button or an automatic rule. */
    ON_DEMAND
}
