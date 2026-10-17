package com.telerad.storage;

import com.telerad.study.InstanceRepository;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GzipVariantServiceTest {
    @Mock ObjectStorageService storage;
    @Mock InstanceRepository instanceRepository;
    @InjectMocks GzipVariantService service;

    @TempDir Path tmp;

    /** A real Part-10 file (preamble + file meta), as stored by the upload path. */
    private byte[] dicom(String tsuid, byte[] pixels) throws Exception {
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        ds.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3");
        ds.setInt(Tag.Rows, VR.US, 64);
        ds.setInt(Tag.Columns, VR.US, 64);
        if (pixels != null) ds.setBytes(Tag.PixelData, VR.OW, pixels);
        File f = tmp.resolve(UUID.randomUUID() + ".dcm").toFile();
        try (DicomOutputStream dos = new DicomOutputStream(f)) {
            dos.writeDataset(ds.createFileMetaInformation(tsuid), ds);
        }
        return Files.readAllBytes(f.toPath());
    }

    @Test
    void uncompressedObjectGetsGzipCopyThatRestoresOriginalBytes() throws Exception {
        byte[] data = dicom(UID.ExplicitVRLittleEndian, new byte[64 * 64 * 2]);
        GzipVariantService.Result r = service.createVariant("t/a.dcm", data);

        assertThat(r.transferSyntaxUid()).isEqualTo(UID.ExplicitVRLittleEndian);
        assertThat(r.gzipStorageKey()).isEqualTo("t/a.dcm.gz");
        verify(storage).put(eq("t/a.dcm.gz"), argThat((byte[] gz) -> {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                return java.util.Arrays.equals(in.readAllBytes(), data);
            } catch (Exception e) {
                return false;
            }
        }), eq("application/gzip"));
    }

    @Test
    void incompressibleObjectIsSkipped() throws Exception {
        byte[] noise = new byte[64 * 64 * 2];
        new java.util.Random(1).nextBytes(noise);
        GzipVariantService.Result r = service.createVariant("t/b.dcm", dicom(UID.ExplicitVRLittleEndian, noise));

        assertThat(r.gzipStorageKey()).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void reportsRealTransferSyntaxFromFileMeta() throws Exception {
        GzipVariantService.Result r = service.createVariant("t/c.dcm", dicom(UID.JPEGLSLossless, null));

        assertThat(r.transferSyntaxUid()).isEqualTo(UID.JPEGLSLossless);
        assertThat(r.gzipStorageKey()).isNull();
        verifyNoInteractions(storage);
    }
}
