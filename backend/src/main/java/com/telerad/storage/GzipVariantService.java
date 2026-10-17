package com.telerad.storage;

import com.telerad.study.InstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps a gzip copy next to each uncompressed DICOM object so image downloads use about half
 * the bandwidth. The stored DICOM is never changed; the copy is served with
 * Content-Encoding: gzip and the browser restores the original bytes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GzipVariantService {
    /** Native (unencapsulated) pixel data; encapsulated syntaxes are already compressed. */
    private static final Set<String> NATIVE_SYNTAXES = Set.of(
            UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndian);
    /** Keep the copy only when it saves at least this share of the bytes. */
    private static final double MIN_SAVING = 0.10;

    private final ObjectStorageService storage;
    private final InstanceRepository instanceRepository;

    public record Result(String transferSyntaxUid, String gzipStorageKey) {}

    /** Read the object's real transfer syntax and store a gzip copy when it pays off. */
    public Result createVariant(String storageKey, byte[] data) throws IOException {
        String tsuid;
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(data))) {
            tsuid = GzipVariantService.fileTransferSyntax(dis);
        }
        if (!NATIVE_SYNTAXES.contains(tsuid)) return new Result(tsuid, null);
        byte[] gz = gzip(data);
        if (gz.length > data.length * (1 - MIN_SAVING)) return new Result(tsuid, null);
        String gzKey = storageKey + ".gz";
        storage.put(gzKey, gz, "application/gzip");
        return new Result(tsuid, gzKey);
    }

    /**
     * Process up to {@code limit} instances that were stored before gzip copies existed
     * (also corrects their recorded transfer syntax). Returns counts and what is left.
     */
    public Map<String, Object> backfill(int limit, int threads) throws InterruptedException {
        long started = System.currentTimeMillis();
        List<UUID> ids = instanceRepository.findIdsWithUncheckedEncodings(PageRequest.of(0, limit));
        AtomicInteger gzipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, 8)));
        try {
            List<Future<?>> futures = ids.stream().<Future<?>>map(id -> pool.submit(() -> {
                try {
                    String key = instanceRepository.findStorageKeyById(id).orElseThrow();
                    Result r = createVariant(key, storage.getBytes(key));
                    instanceRepository.markEncodingsChecked(id, r.transferSyntaxUid(), r.gzipStorageKey());
                    if (r.gzipStorageKey() != null) gzipped.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("gzip backfill failed for instance {}: {}", id, e.getMessage());
                }
            })).toList();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // counted above
                }
            }
        } finally {
            pool.shutdown();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", ids.size());
        out.put("gzipped", gzipped.get());
        out.put("failed", failed.get());
        out.put("remaining", instanceRepository.countWithUncheckedEncodings());
        out.put("ms", System.currentTimeMillis() - started);
        return out;
    }

    /**
     * The object's transfer syntax from its file meta information. {@code getTransferSyntax()} is not it:
     * for encapsulated (compressed) syntaxes it reports the dataset encoding, Explicit VR Little Endian.
     */
    public static String fileTransferSyntax(DicomInputStream dis) throws IOException {
        Attributes fmi = dis.getFileMetaInformation();
        String tsuid = fmi != null ? fmi.getString(Tag.TransferSyntaxUID) : null;
        return tsuid != null ? tsuid : dis.getTransferSyntax();
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2);
        try (GZIPOutputStream gz = new GZIPOutputStream(out, 64 * 1024)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
