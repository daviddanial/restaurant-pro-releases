package com.telerad.export;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class ExportService {
    private final ExportJobRepository exportJobRepository;
    private final StudyRepository studyRepository;
    private final SeriesQueryRepository seriesQueryRepository;
    private final SeriesRepository seriesRepository;
    private final InstanceRepository instanceRepository;
    private final ObjectStorageService storage;
    private final AuditService auditService;
    private final ExportWorker exportWorker;

    public ExportService(
            ExportJobRepository exportJobRepository,
            StudyRepository studyRepository,
            SeriesQueryRepository seriesQueryRepository,
            SeriesRepository seriesRepository,
            InstanceRepository instanceRepository,
            ObjectStorageService storage,
            AuditService auditService,
            @Lazy ExportWorker exportWorker) {
        this.exportJobRepository = exportJobRepository;
        this.studyRepository = studyRepository;
        this.seriesQueryRepository = seriesQueryRepository;
        this.seriesRepository = seriesRepository;
        this.instanceRepository = instanceRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.exportWorker = exportWorker;
    }

    public record StartRequest(ExportScope scope, String seriesInstanceUid, String sopInstanceUid) {}

    public record ExportJobDto(
            UUID id,
            UUID studyId,
            ExportScope scope,
            ExportJobStatus status,
            int totalObjects,
            int completedObjects,
            String errorMessage,
            boolean downloadReady
    ) {}

    @Transactional
    public ExportJobDto start(UUID studyId, StartRequest request) {
        if (request == null || request.scope() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope is required");
        }
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UserPrincipal user = UserPrincipal.current();
        assertCanView(user, study);

        List<Instance> instances = resolveInstances(study, request);
        if (instances.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No DICOM objects to export");
        }

        ExportJob job = ExportJob.builder()
                .tenantId(study.getTenantId())
                .studyId(study.getId())
                .requestedBy(user.getId())
                .scope(request.scope())
                .seriesInstanceUid(request.seriesInstanceUid())
                .sopInstanceUid(request.sopInstanceUid())
                .status(ExportJobStatus.PENDING)
                .totalObjects(instances.size())
                .completedObjects(0)
                .build();
        exportJobRepository.save(job);

        auditService.record(study.getTenantId(), user.getId(), "EXPORT_DICOM", "Study", studyId.toString(),
                request.scope() + " objects=" + instances.size(), null);

        UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                exportWorker.run(jobId);
            }
        });

        return toDto(job);
    }

    @Transactional(readOnly = true)
    public ExportJobDto status(UUID jobId) {
        ExportJob job = loadAuthorized(jobId);
        return toDto(job);
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID jobId) {
        ExportJob job = loadAuthorized(jobId);
        if (job.getStatus() != ExportJobStatus.DONE || job.getResultKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Export not ready");
        }
        auditService.record(job.getTenantId(), UserPrincipal.current().getId(), "DOWNLOAD_EXPORT",
                "ExportJob", jobId.toString(), job.getScope().name(), null);
        return storage.getBytes(job.getResultKey());
    }

    @Transactional
    public void process(UUID jobId) {
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() == ExportJobStatus.DONE || job.getStatus() == ExportJobStatus.FAILED) return;

        job.setStatus(ExportJobStatus.RUNNING);
        exportJobRepository.save(job);

        try {
            Study study = studyRepository.findById(job.getStudyId())
                    .orElseThrow(() -> new IllegalStateException("Study missing"));
            List<Instance> instances = resolveInstances(study, new StartRequest(
                    job.getScope(), job.getSeriesInstanceUid(), job.getSopInstanceUid()));

            job.setTotalObjects(instances.size());
            exportJobRepository.save(job);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                byte[] buf = new byte[64 * 1024];
                int done = 0;
                for (Instance inst : instances) {
                    Series series = inst.getSeries();
                    String entryName = study.getStudyInstanceUid()
                            + "/" + series.getSeriesInstanceUid()
                            + "/" + inst.getSopInstanceUid() + ".dcm";
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = storage.getStream(inst.getStorageKey())) {
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                    done++;
                    job.setCompletedObjects(done);
                    // Flush progress periodically so the UI can poll.
                    if (done == instances.size() || done % 5 == 0) {
                        exportJobRepository.save(job);
                    }
                }
            }

            String resultKey = study.getTenantId() + "/" + study.getStudyInstanceUid()
                    + "/exports/" + job.getId() + ".zip";
            storage.put(resultKey, bos.toByteArray(), "application/zip");
            job.setResultKey(resultKey);
            job.setStatus(ExportJobStatus.DONE);
            job.setFinishedAt(Instant.now());
            exportJobRepository.save(job);
        } catch (Exception e) {
            log.error("Export job {} failed", jobId, e);
            job.setStatus(ExportJobStatus.FAILED);
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            job.setFinishedAt(Instant.now());
            exportJobRepository.save(job);
        }
    }

    private List<Instance> resolveInstances(Study study, StartRequest request) {
        return switch (request.scope()) {
            case IMAGE -> {
                if (request.sopInstanceUid() == null || request.sopInstanceUid().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sopInstanceUid is required");
                }
                Instance inst = instanceRepository.findBySopInstanceUid(request.sopInstanceUid())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instance not found"));
                if (!inst.getSeries().getStudy().getId().equals(study.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOP does not belong to study");
                }
                yield List.of(inst);
            }
            case SERIES -> {
                if (request.seriesInstanceUid() == null || request.seriesInstanceUid().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seriesInstanceUid is required");
                }
                Series series = seriesRepository
                        .findByStudyIdAndSeriesInstanceUid(study.getId(), request.seriesInstanceUid())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found"));
                yield instanceRepository.findBySeriesIdOrderByInstanceNumberAsc(series.getId());
            }
            case STUDY -> {
                List<Instance> all = new ArrayList<>();
                for (Series series : seriesQueryRepository.findByStudyIdOrdered(study.getId())) {
                    all.addAll(instanceRepository.findBySeriesIdOrderByInstanceNumberAsc(series.getId()));
                }
                yield all;
            }
        };
    }

    private ExportJob loadAuthorized(UUID jobId) {
        ExportJob job = exportJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Study study = studyRepository.findById(job.getStudyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertCanView(UserPrincipal.current(), study);
        return job;
    }

    private void assertCanView(UserPrincipal user, Study study) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private ExportJobDto toDto(ExportJob job) {
        return new ExportJobDto(
                job.getId(),
                job.getStudyId(),
                job.getScope(),
                job.getStatus(),
                job.getTotalObjects(),
                job.getCompletedObjects(),
                job.getErrorMessage(),
                job.getStatus() == ExportJobStatus.DONE && job.getResultKey() != null
        );
    }

    private static String truncate(String msg, int max) {
        if (msg == null) return "Export failed";
        return msg.length() <= max ? msg : msg.substring(0, max);
    }
}
