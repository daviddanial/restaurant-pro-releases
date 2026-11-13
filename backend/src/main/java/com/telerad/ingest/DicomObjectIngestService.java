package com.telerad.ingest;

import com.telerad.common.StudyPriority;
import com.telerad.common.StudySource;
import com.telerad.storage.GzipVariantService;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.InstanceRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import lombok.RequiredArgsConstructor;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * Shared DICOM byte → MinIO + upsert path used by portal upload and gateway ingest.
 */
@Service
@RequiredArgsConstructor
public class DicomObjectIngestService {
    private final ObjectStorageService storage;
    private final IngestPublisher publisher;
    private final StudyRepository studyRepository;
    private final InstanceRepository instanceRepository;
    private final IngestWorker ingestWorker;

    public record IngestOneResult(
            String sopInstanceUid,
            String studyInstanceUid,
            String seriesInstanceUid,
            boolean duplicate,
            boolean stored
    ) {}

    @Transactional
    public IngestOneResult ingestBytes(
            UUID tenantId,
            byte[] data,
            StudySource source,
            StudyPriority priority,
            String publishSource
    ) throws Exception {
        Attributes attrs;
        String tsuid;
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(data))) {
            tsuid = GzipVariantService.fileTransferSyntax(dis);
            attrs = dis.readDataset(-1, -1);
        }
        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopUid = attrs.getString(Tag.SOPInstanceUID);
        if (studyUid == null || seriesUid == null || sopUid == null) {
            throw new IllegalArgumentException("Missing Study/Series/SOP Instance UID");
        }

        // An index-only row (image on the hospital server, no cloud copy yet) is not a duplicate.
        boolean duplicate = instanceRepository.findBySopInstanceUid(sopUid)
                .map(existing -> existing.getStorageKey() != null)
                .orElse(false);
        String key = tenantId + "/" + studyUid + "/" + seriesUid + "/" + sopUid + ".dcm";
        if (!duplicate) {
            storage.put(key, data, "application/dicom");
        }

        Study study = ingestWorker.upsertFromDataset(tenantId, key, attrs, tsuid, source);
        if (priority != null && priority != StudyPriority.ROUTINE) {
            study.setPriority(priority);
            studyRepository.save(study);
        }
        if (!duplicate) {
            // After commit: a worker that runs earlier sees no instance row and inserts a second copy.
            runAfterCommit(() -> publisher.publish(tenantId, key, studyUid, publishSource, null));
        }
        return new IngestOneResult(sopUid, studyUid, seriesUid, duplicate, !duplicate);
    }

    /**
     * Index one instance kept on a hospital server: the cloud gets the study/series/instance rows
     * from the DICOM header (no pixel data is sent) but no file. The file can be uploaded later
     * through {@link #ingestBytes}, which fills in the storage key of the same row.
     */
    @Transactional
    public IngestOneResult indexHeader(UUID tenantId, byte[] header) throws Exception {
        Attributes attrs;
        String tsuid;
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(header))) {
            tsuid = GzipVariantService.fileTransferSyntax(dis);
            attrs = dis.readDataset(-1, -1);
        }
        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopUid = attrs.getString(Tag.SOPInstanceUID);
        if (studyUid == null || seriesUid == null || sopUid == null) {
            throw new IllegalArgumentException("Missing Study/Series/SOP Instance UID");
        }
        boolean known = instanceRepository.findBySopInstanceUid(sopUid).isPresent();
        ingestWorker.upsertFromDataset(tenantId, null, attrs, tsuid, StudySource.GATEWAY);
        return new IngestOneResult(sopUid, studyUid, seriesUid, known, false);
    }

    private static void runAfterCommit(Runnable action) {
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
}
