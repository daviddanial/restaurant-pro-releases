package com.telerad.order;

/** When a scanned study goes to the radiologists (per facility). */
public enum ReadyMode {
    /** As soon as the images stop arriving. "Scan complete" is optional. */
    AUTO,
    /** Only after the MRT clicks "Scan complete". */
    WAIT_FOR_MRT
}
