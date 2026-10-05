package com.telerad.common;

public enum StudyStatus {
    ORDERED,      // created from a physician order (Feya), DICOM not yet received
    RECEIVED,
    READY,
    IN_PROGRESS,
    REPORTED,
    DELIVERED
}
