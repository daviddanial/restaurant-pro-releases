package com.telerad.ingest;

import com.telerad.common.StudySource;
import com.telerad.common.StudyStatus;
import com.telerad.notify.NotificationService;
import com.telerad.notify.SlaService;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestWorker {
    private final ObjectStorageService storage;
    private final StudyRepository studyRepository;
    private final SeriesRepository seriesRepository;
    private final InstanceRepository instanceRepository;
    private final PatientRepository patientRepository;
    private final SlaService slaService;
    private final NotificationService notificationService;

    @RabbitListener(queues = "${telerad.rabbit.ingest-queue}")
    @Transactional
    public void handle(StudyIngestMessage message) {
        log.info("Ingesting object {} for tenant {}", message.storageKey(), message.tenantId());
        try {
            byte[] bytes = storage.getBytes(message.storageKey());
            Attributes attrs;
            try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(bytes))) {
                attrs = dis.readDataset(-1, -1);
            }
            upsertFromDataset(message.tenantId(), message.storageKey(), attrs,
                    "CSTORE".equalsIgnoreCase(message.source()) ? StudySource.CSTORE : StudySource.UPLOAD);
        } catch (Exception e) {
            log.error("Failed to ingest {}: {}", message.storageKey(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public Study upsertFromDataset(UUID tenantId, String storageKey, Attributes attrs, StudySource source) {
        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopUid = attrs.getString(Tag.SOPInstanceUID);
        if (studyUid == null || seriesUid == null || sopUid == null) {
            throw new IllegalArgumentException("Missing required DICOM UIDs");
        }

        Optional<Instance> existing = instanceRepository.findBySopInstanceUid(sopUid);
        if (existing.isPresent()) {
            Instance inst = existing.get();
            inst.setStorageKey(storageKey);
            instanceRepository.save(inst);
            Study study = inst.getSeries().getStudy();
            if (study.getStatus() != StudyStatus.REPORTED && study.getStatus() != StudyStatus.DELIVERED) {
                bumpReady(study);
            }
            return study;
        }

        Patient patient = upsertPatient(tenantId, attrs);

        // Prefer an existing study (same StudyInstanceUID). If the DICOM arrives cold,
        // try to bind it to a physician-ordered study (status ORDERED) via accession number —
        // this is how a Feya order becomes the study once the facility uploads the images.
        Study study = studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, studyUid)
                .orElseGet(() -> {
                    String accession = attrs.getString(Tag.AccessionNumber);
                    if (accession != null) {
                        Study ordered = studyRepository
                                .findByTenantIdAndAccessionNumber(tenantId, accession)
                                .filter(s -> s.getStatus() == StudyStatus.ORDERED)
                                .orElse(null);
                        if (ordered != null) {
                            ordered.setStudyInstanceUid(studyUid);
                            return ordered;
                        }
                    }
                    return Study.builder()
                            .tenantId(tenantId)
                            .studyInstanceUid(studyUid)
                            .source(source)
                            .status(StudyStatus.RECEIVED)
                            .build();
                });

        if (study.getStatus() == StudyStatus.REPORTED || study.getStatus() == StudyStatus.DELIVERED) {
            // do not regress; still store instance under existing study
            log.warn("Study {} already reported; adding instance without status change", studyUid);
        }

        study.setPatientId(patient.getId());
        study.setAccessionNumber(attrs.getString(Tag.AccessionNumber));
        study.setStudyDescription(attrs.getString(Tag.StudyDescription));
        String modality = attrs.getString(Tag.Modality);
        if (modality != null) study.setModality(modality);
        study.setStudyDate(parseDate(attrs.getString(Tag.StudyDate)));
        final Study savedStudy = studyRepository.save(study);

        Series series = seriesRepository.findByStudyIdAndSeriesInstanceUid(savedStudy.getId(), seriesUid)
                .orElseGet(() -> Series.builder()
                        .study(savedStudy)
                        .seriesInstanceUid(seriesUid)
                        .build());
        series.setModality(modality);
        series.setSeriesNumber(attrs.getInt(Tag.SeriesNumber, 0) == 0 ? null : attrs.getInt(Tag.SeriesNumber, 0));
        series.setSeriesDescription(attrs.getString(Tag.SeriesDescription));
        series = seriesRepository.save(series);

        Instance instance = Instance.builder()
                .series(series)
                .sopInstanceUid(sopUid)
                .sopClassUid(attrs.getString(Tag.SOPClassUID))
                .instanceNumber(attrs.getInt(Tag.InstanceNumber, 0) == 0 ? null : attrs.getInt(Tag.InstanceNumber, 0))
                .rows(attrs.getInt(Tag.Rows, 0) == 0 ? null : attrs.getInt(Tag.Rows, 0))
                .columns(attrs.getInt(Tag.Columns, 0) == 0 ? null : attrs.getInt(Tag.Columns, 0))
                .numberOfFrames(Math.max(1, attrs.getInt(Tag.NumberOfFrames, 1)))
                .cineRate(readPositiveDouble(attrs, Tag.CineRate, Tag.RecommendedDisplayFrameRate))
                .frameTime(readPositiveDouble(attrs, Tag.FrameTime))
                .storageKey(storageKey)
                .transferSyntaxUid(attrs.getString(Tag.TransferSyntaxUID, UID.ExplicitVRLittleEndian))
                .build();
        instanceRepository.save(instance);

        series.setInstanceCount(series.getInstanceCount() + 1);
        seriesRepository.save(series);
        savedStudy.setInstanceCount(savedStudy.getInstanceCount() + 1);
        if (savedStudy.getStatus() != StudyStatus.REPORTED && savedStudy.getStatus() != StudyStatus.DELIVERED
                && savedStudy.getStatus() != StudyStatus.IN_PROGRESS) {
            bumpReady(savedStudy);
        } else {
            studyRepository.save(savedStudy);
        }
        return savedStudy;
    }

    private void bumpReady(Study study) {
        boolean firstReady = study.getReadyAt() == null;
        study.setStatus(StudyStatus.READY);
        if (firstReady) {
            study.setReadyAt(Instant.now());
            slaService.applySlaDeadline(study);
        }
        studyRepository.save(study);
        if (firstReady) {
            Study ready = study;
            runAfterCommit(() -> notificationService.notifyStudyReady(ready));
        }
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private Patient upsertPatient(UUID tenantId, Attributes attrs) {
        String pid = Optional.ofNullable(attrs.getString(Tag.PatientID)).orElse("UNKNOWN");
        return patientRepository.findByTenantIdAndPatientId(tenantId, pid)
                .map(p -> {
                    p.setPatientName(attrs.getString(Tag.PatientName));
                    p.setSex(attrs.getString(Tag.PatientSex));
                    p.setBirthDate(parseDate(attrs.getString(Tag.PatientBirthDate)));
                    return patientRepository.save(p);
                })
                .orElseGet(() -> patientRepository.save(Patient.builder()
                        .tenantId(tenantId)
                        .patientId(pid)
                        .patientName(attrs.getString(Tag.PatientName))
                        .sex(attrs.getString(Tag.PatientSex))
                        .birthDate(parseDate(attrs.getString(Tag.PatientBirthDate)))
                        .build()));
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 8) return null;
        try {
            return LocalDate.parse(raw.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** First positive numeric value among the given DICOM tags, or null. */
    private static Double readPositiveDouble(Attributes attrs, int... tags) {
        for (int tag : tags) {
            if (!attrs.containsValue(tag)) continue;
            try {
                double v = attrs.getDouble(tag, Double.NaN);
                if (!Double.isNaN(v) && v > 0) return v;
            } catch (Exception ignored) {
                // fall through
            }
            try {
                String s = attrs.getString(tag);
                if (s != null && !s.isBlank()) {
                    double v = Double.parseDouble(s.trim().split("\\\\")[0]);
                    if (v > 0) return v;
                }
            } catch (Exception ignored) {
                // try next tag
            }
        }
        return null;
    }
}
