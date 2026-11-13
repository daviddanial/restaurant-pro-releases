package com.telerad.common;

/** Who is the long-term archive for a facility. */
public enum EdgeStorageMode {
    /** Our hospital server keeps the images for good (unless a local retention is set). */
    ARCHIVE,
    /** The facility's own PACS is the archive; our hospital server only caches images. */
    CACHE
}
