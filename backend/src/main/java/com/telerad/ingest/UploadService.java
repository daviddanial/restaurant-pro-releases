package com.telerad.ingest;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyPriority;
import com.telerad.common.StudySource;
import com.telerad.common.StudyStatus;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {
    private final ObjectStorageService storage;
    private final IngestPublisher publisher;
    private final StudyRepository studyRepository;
    private final AuditService auditService;
    private final IngestWorker ingestWorker;

    @Transactional
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
    public Map<String, Object> upload(UUID hospitalId, MultipartFile[] files, StudyPriority priority) {
        UserPrincipal user = UserPrincipal.current();
        UUID tenantId = resolveTenant(user, hospitalId);
        int stored = 0;
        Set<String> studyUids = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("upload.dcm").toLowerCase();
            try {
                if (name.endsWith(".zip")) {
                    stored += processZip(tenantId, file.getBytes(), studyUids, priority);
                } else {
                    stored += processDicomBytes(tenantId, file.getBytes(), studyUids, priority);
                }
            } catch (Exception e) {
                log.warn("Upload failed for {}: {}", name, e.getMessage());
                errors.add(name + ": " + e.getMessage());
            }
        }

        auditService.record(tenantId, user.getId(), "UPLOAD", "Study", String.join(",", studyUids),
                "files=" + files.length + ",stored=" + stored, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("instancesStored", stored);
        result.put("studyInstanceUids", studyUids);
        result.put("errors", errors);
        return result;
    }

    private int processZip(UUID tenantId, byte[] zipBytes, Set<String> studyUids, StudyPriority priority) throws Exception {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] data = readAll(zis);
                if (data.length < 132) continue;
                try {
                    count += processDicomBytes(tenantId, data, studyUids, priority);
                } catch (Exception e) {
                    log.debug("Skipping zip entry {}: {}", entry.getName(), e.getMessage());
                }
            }
        }
        return count;
    }

    private int processDicomBytes(UUID tenantId, byte[] data, Set<String> studyUids, StudyPriority priority) throws Exception {
        Attributes attrs;
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(data))) {
            attrs = dis.readDataset(-1, -1);
        }
        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopUid = attrs.getString(Tag.SOPInstanceUID);
        if (studyUid == null || sopUid == null) {
            throw new IllegalArgumentException("Not a valid DICOM object");
        }
        String key = tenantId + "/" + studyUid + "/" + seriesUid + "/" + sopUid + ".dcm";
        storage.put(key, data, "application/dicom");

        // Process synchronously for reliable MVP, also publish for async reprocessing
        Study study = ingestWorker.upsertFromDataset(tenantId, key, attrs, StudySource.UPLOAD);
        if (priority != null && priority != StudyPriority.ROUTINE) {
            study.setPriority(priority);
            studyRepository.save(study);
        }
        publisher.publish(tenantId, key, studyUid, "UPLOAD", null);
        studyUids.add(studyUid);
        return 1;
    }

    private UUID resolveTenant(UserPrincipal user, UUID hospitalId) {
        if (user.getRole() == Role.ADMIN) {
            return hospitalId;
        }
        if (user.getTenantId() == null || !user.getTenantId().equals(hospitalId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot upload for another hospital");
        }
        return user.getTenantId();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
