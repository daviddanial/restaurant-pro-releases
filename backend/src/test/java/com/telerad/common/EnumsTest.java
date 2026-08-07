package com.telerad.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnumsTest {
    @Test
    void roles() {
        assertThat(Role.values()).containsExactly(Role.ADMIN, Role.HOSPITAL_USER, Role.RADIOLOGIST);
    }

    @Test
    void studyStatuses() {
        assertThat(StudyStatus.values()).contains(
                StudyStatus.RECEIVED, StudyStatus.READY, StudyStatus.IN_PROGRESS,
                StudyStatus.REPORTED, StudyStatus.DELIVERED);
    }

    @Test
    void prioritiesAndSources() {
        assertThat(StudyPriority.valueOf("STAT")).isEqualTo(StudyPriority.STAT);
        assertThat(StudySource.UPLOAD.name()).isEqualTo("UPLOAD");
        assertThat(ReportStatus.DRAFT).isNotEqualTo(ReportStatus.SIGNED);
        assertThat(DeliveryChannel.PORTAL).isNotNull();
        assertThat(DeliveryStatus.PENDING).isNotNull();
    }
}
