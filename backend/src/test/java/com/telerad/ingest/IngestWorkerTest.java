package com.telerad.ingest;

import com.telerad.common.StudySource;
import com.telerad.common.StudyStatus;
import com.telerad.notify.NotificationService;
import com.telerad.notify.SlaService;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.*;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestWorkerTest {
    @Mock ObjectStorageService storage;
    @Mock StudyRepository studyRepository;
    @Mock SeriesRepository seriesRepository;
    @Mock InstanceRepository instanceRepository;
    @Mock PatientRepository patientRepository;
    @Mock SlaService slaService;
    @Mock NotificationService notificationService;
    @InjectMocks IngestWorker ingestWorker;

    private UUID tenantId;
    private Attributes attrs;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        attrs = new Attributes();
        attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.10008.1.1");
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.10008.1.2");
        attrs.setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.10008.1.3");
        attrs.setString(Tag.PatientID, VR.LO, "MRN001");
        attrs.setString(Tag.PatientName, VR.PN, "DOE^JOHN");
        attrs.setString(Tag.Modality, VR.CS, "CT");
        attrs.setString(Tag.StudyDate, VR.DA, "20240115");
        attrs.setString(Tag.AccessionNumber, VR.SH, "ACC100");
        attrs.setInt(Tag.SeriesNumber, VR.IS, 1);
        attrs.setInt(Tag.InstanceNumber, VR.IS, 1);
        attrs.setInt(Tag.Rows, VR.US, 512);
        attrs.setInt(Tag.Columns, VR.US, 512);
    }

    @Test
    void upsertFromDataset_createsStudySeriesInstance() {
        when(instanceRepository.findBySopInstanceUid("1.2.840.10008.1.3")).thenReturn(Optional.empty());
        when(patientRepository.findByTenantIdAndPatientId(tenantId, "MRN001")).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, "1.2.840.10008.1.1"))
                .thenReturn(Optional.empty());
        when(studyRepository.save(any(Study.class))).thenAnswer(inv -> {
            Study s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(seriesRepository.findByStudyIdAndSeriesInstanceUid(any(), eq("1.2.840.10008.1.2")))
                .thenReturn(Optional.empty());
        when(seriesRepository.save(any(Series.class))).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(instanceRepository.save(any(Instance.class))).thenAnswer(inv -> inv.getArgument(0));

        Study study = ingestWorker.upsertFromDataset(tenantId, "tenant/study/series/sop.dcm", attrs, StudySource.UPLOAD);

        assertThat(study.getStatus()).isEqualTo(StudyStatus.READY);
        assertThat(study.getReadyAt()).isNotNull();
        assertThat(study.getModality()).isEqualTo("CT");
        assertThat(study.getAccessionNumber()).isEqualTo("ACC100");
        assertThat(study.getInstanceCount()).isEqualTo(1);
        verify(slaService).applySlaDeadline(study);
        verify(notificationService).notifyStudyReady(study);
        verify(instanceRepository).save(argThat(i ->
                "1.2.840.10008.1.3".equals(i.getSopInstanceUid())
                        && Integer.valueOf(512).equals(i.getRows())));
    }

    @Test
    void upsertFromDataset_requiresUids() {
        Attributes bad = new Attributes();
        bad.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        assertThatThrownBy(() -> ingestWorker.upsertFromDataset(tenantId, "k", bad, StudySource.UPLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required DICOM UIDs");
    }

    @Test
    void upsertFromDataset_existingInstanceUpdatesStorageKey() {
        Study existingStudy = Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .studyInstanceUid("1.2.840.10008.1.1")
                .status(StudyStatus.READY)
                .readyAt(java.time.Instant.now())
                .build();
        Series series = Series.builder().id(UUID.randomUUID()).study(existingStudy)
                .seriesInstanceUid("1.2.840.10008.1.2").build();
        Instance instance = Instance.builder().id(UUID.randomUUID()).series(series)
                .sopInstanceUid("1.2.840.10008.1.3").storageKey("old-key").build();

        when(instanceRepository.findBySopInstanceUid("1.2.840.10008.1.3")).thenReturn(Optional.of(instance));
        when(instanceRepository.save(instance)).thenReturn(instance);
        when(studyRepository.save(existingStudy)).thenReturn(existingStudy);

        Study result = ingestWorker.upsertFromDataset(tenantId, "new-key", attrs, StudySource.UPLOAD);

        assertThat(result).isSameAs(existingStudy);
        assertThat(instance.getStorageKey()).isEqualTo("new-key");
        verify(slaService, never()).applySlaDeadline(any());
        verify(notificationService, never()).notifyStudyReady(any());
    }

    @Test
    void upsertFromDataset_doesNotRegressReportedStudy() {
        Study reported = Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .studyInstanceUid("1.2.840.10008.1.1")
                .status(StudyStatus.REPORTED)
                .build();
        when(instanceRepository.findBySopInstanceUid(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByTenantIdAndPatientId(any(), anyString())).thenReturn(Optional.empty());
        when(patientRepository.save(any())).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, "1.2.840.10008.1.1"))
                .thenReturn(Optional.of(reported));
        when(studyRepository.save(reported)).thenReturn(reported);
        when(seriesRepository.findByStudyIdAndSeriesInstanceUid(any(), anyString())).thenReturn(Optional.empty());
        when(seriesRepository.save(any())).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Study result = ingestWorker.upsertFromDataset(tenantId, "key", attrs, StudySource.CSTORE);
        assertThat(result.getStatus()).isEqualTo(StudyStatus.REPORTED);
        verify(notificationService, never()).notifyStudyReady(any());
    }
}
