package com.telerad.dicom;

import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Instance;
import com.telerad.study.InstanceRepository;
import com.telerad.study.Series;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReader;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReaderSpi;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/dicom")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WadoController {
    private static final Set<String> UNSUPPORTED_TRANSFER_SYNTAXES = Set.of(
            UID.JPEGLosslessSV1,           // 1.2.840.10008.1.2.4.70
            UID.JPEGLossless,              // 1.2.840.10008.1.2.4.57
            UID.JPEGLSLossless,            // 1.2.840.10008.1.2.4.80
            UID.JPEGLSNearLossless,        // 1.2.840.10008.1.2.4.81
            UID.JPEG2000Lossless,          // 1.2.840.10008.1.2.4.90
            UID.JPEG2000,                  // 1.2.840.10008.1.2.4.91
            UID.JPEG2000MCLossless,        // 1.2.840.10008.1.2.4.92
            UID.JPEG2000MC                 // 1.2.840.10008.1.2.4.93
    );

    private final StudyRepository studyRepository;
    private final InstanceRepository instanceRepository;
    private final ObjectStorageService storage;

    @GetMapping("/studies/{studyUID}")
    public Map<String, Object> studyMetadata(@PathVariable String studyUID) {
        Study study = studyRepository.findByStudyInstanceUid(studyUID)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertCanView(UserPrincipal.current(), study);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("StudyInstanceUID", study.getStudyInstanceUid());
        body.put("id", study.getId());
        body.put("Modality", study.getModality());
        body.put("status", study.getStatus());
        return body;
    }

    @GetMapping("/studies/{studyUID}/series/{seriesUID}/instances/{sopUID}")
    public ResponseEntity<byte[]> instance(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String sopUID) {
        Instance instance = requireInstance(studyUID, seriesUID, sopUID);
        byte[] data = storage.getBytes(instance.getStorageKey());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/dicom"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sopUID + ".dcm\"")
                .body(data);
    }

    @GetMapping("/studies/{studyUID}/series/{seriesUID}/instances/{sopUID}/frames/{frame}")
    public ResponseEntity<byte[]> frame(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String sopUID,
            @PathVariable int frame,
            @RequestParam(required = false) Double windowCenter,
            @RequestParam(required = false) Double windowWidth) {
        Instance instance = requireInstance(studyUID, seriesUID, sopUID);
        int frameNumber = Math.max(1, frame);
        if (frameNumber > Math.max(1, instance.getNumberOfFrames())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Frame out of range");
        }
        try {
            byte[] dicom = storage.getBytes(instance.getStorageKey());
            byte[] jpeg = renderFrameAsJpeg(dicom, frameNumber - 1, windowCenter, windowWidth);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(jpeg);
        } catch (UnsupportedTransferSyntaxException e) {
            log.warn("Unsupported transfer syntax for frame render: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Frame render failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to render frame");
        }
    }

    @GetMapping("/instances/{sopUID}/metadata")
    public Map<String, Object> instanceMetadata(@PathVariable String sopUID) {
        Instance instance = instanceRepository.findBySopInstanceUid(sopUID)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Study study = instance.getSeries().getStudy();
        assertCanView(UserPrincipal.current(), study);
        try {
            byte[] dicom = storage.getBytes(instance.getStorageKey());
            Attributes attrs;
            try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(dicom))) {
                attrs = dis.readDataset(-1, Tag.PixelData);
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("SOPInstanceUID", sopUID);
            meta.put("StudyInstanceUID", study.getStudyInstanceUid());
            meta.put("SeriesInstanceUID", instance.getSeries().getSeriesInstanceUid());
            meta.put("Rows", attrs.getInt(Tag.Rows, 0));
            meta.put("Columns", attrs.getInt(Tag.Columns, 0));
            meta.put("BitsAllocated", attrs.getInt(Tag.BitsAllocated, 8));
            meta.put("BitsStored", attrs.getInt(Tag.BitsStored, attrs.getInt(Tag.BitsAllocated, 8)));
            meta.put("PixelRepresentation", attrs.getInt(Tag.PixelRepresentation, 0));
            meta.put("PhotometricInterpretation", attrs.getString(Tag.PhotometricInterpretation));
            meta.put("WindowCenter", attrs.getString(Tag.WindowCenter));
            meta.put("WindowWidth", attrs.getString(Tag.WindowWidth));
            if (attrs.containsValue(Tag.PixelSpacing)) {
                meta.put("PixelSpacing", attrs.getStrings(Tag.PixelSpacing));
            }
            if (attrs.containsValue(Tag.ImagerPixelSpacing)) {
                meta.put("ImagerPixelSpacing", attrs.getStrings(Tag.ImagerPixelSpacing));
            }
            if (attrs.containsValue(Tag.RescaleSlope)) {
                meta.put("RescaleSlope", attrs.getDouble(Tag.RescaleSlope, 1.0));
            }
            if (attrs.containsValue(Tag.RescaleIntercept)) {
                meta.put("RescaleIntercept", attrs.getDouble(Tag.RescaleIntercept, 0.0));
            }
            meta.put("NumberOfFrames", instance.getNumberOfFrames());
            meta.put("Modality", instance.getSeries().getModality() != null
                    ? instance.getSeries().getModality()
                    : study.getModality());
            Double cineRate = instance.getCineRate();
            if (cineRate == null && attrs.containsValue(Tag.CineRate)) {
                cineRate = attrs.getDouble(Tag.CineRate, Double.NaN);
                if (Double.isNaN(cineRate) || cineRate <= 0) cineRate = null;
            }
            if (cineRate == null && attrs.containsValue(Tag.RecommendedDisplayFrameRate)) {
                cineRate = attrs.getDouble(Tag.RecommendedDisplayFrameRate, Double.NaN);
                if (Double.isNaN(cineRate) || cineRate <= 0) cineRate = null;
            }
            Double frameTime = instance.getFrameTime();
            if (frameTime == null && attrs.containsValue(Tag.FrameTime)) {
                frameTime = attrs.getDouble(Tag.FrameTime, Double.NaN);
                if (Double.isNaN(frameTime) || frameTime <= 0) frameTime = null;
            }
            if (cineRate != null) meta.put("CineRate", cineRate);
            if (frameTime != null) meta.put("FrameTime", frameTime);
            return meta;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private Instance requireInstance(String studyUID, String seriesUID, String sopUID) {
        Instance instance = instanceRepository.findBySopInstanceUid(sopUID)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Series series = instance.getSeries();
        Study study = series.getStudy();
        if (!study.getStudyInstanceUid().equals(studyUID) || !series.getSeriesInstanceUid().equals(seriesUID)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        assertCanView(UserPrincipal.current(), study);
        return instance;
    }

    private void assertCanView(UserPrincipal user, Study study) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private byte[] renderFrameAsJpeg(byte[] dicomBytes, int frameIndex, Double windowCenter, Double windowWidth)
            throws Exception {
        String tsuid;
        Attributes dataset;
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(dicomBytes))) {
            Attributes fmi = dis.getFileMetaInformation();
            tsuid = fmi != null ? fmi.getString(Tag.TransferSyntaxUID) : null;
            dataset = dis.readDataset(-1, -1);
        }
        if (tsuid != null && UNSUPPORTED_TRANSFER_SYNTAXES.contains(tsuid)) {
            throw new UnsupportedTransferSyntaxException(
                    "Transfer syntax " + tsuid + " is not supported for frame preview "
                            + "(JPEG-LS / JPEG 2000 / JPEG Lossless require native codecs)");
        }

        String photometric = normalizePhotometric(dataset.getString(Tag.PhotometricInterpretation));
        boolean monochrome = photometric.startsWith("MONOCHROME");
        Double wlCenter = monochrome ? windowCenter : null;
        Double wlWidth = monochrome ? windowWidth : null;

        try {
            return renderWithDicomImageReader(dicomBytes, frameIndex, wlCenter, wlWidth);
        } catch (Exception primary) {
            log.warn("DicomImageReader failed ({}), trying fallbacks", primary.getMessage());
        }

        try {
            return renderEncapsulatedJpeg(dataset, frameIndex, wlCenter, wlWidth);
        } catch (Exception encapsulated) {
            log.warn("Encapsulated JPEG fallback failed ({})", encapsulated.getMessage());
        }

        if (UID.RLELossless.equals(tsuid)) {
            try {
                return renderRleFrame(dataset, frameIndex, photometric);
            } catch (Exception rle) {
                log.warn("RLE fallback failed ({})", rle.getMessage());
            }
        }

        return renderUncompressedFrame(dataset, frameIndex, photometric, wlCenter, wlWidth);
    }

    private byte[] renderWithDicomImageReader(byte[] dicomBytes, int frameIndex,
                                              Double windowCenter, Double windowWidth) throws Exception {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(dicomBytes))) {
            DicomImageReader reader = new DicomImageReader(new DicomImageReaderSpi());
            reader.setInput(iis);
            int index = Math.min(Math.max(frameIndex, 0), Math.max(0, reader.getNumImages(true) - 1));
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            if (windowCenter != null && windowWidth != null && windowWidth > 0) {
                param.setWindowCenter(windowCenter.floatValue());
                param.setWindowWidth(windowWidth.floatValue());
            }
            BufferedImage image = reader.read(index, param);
            return encodeJpeg(image);
        }
    }

    /** Decode a single encapsulated JPEG Baseline/Extended frame when dcm4che ImageReader is unavailable. */
    private byte[] renderEncapsulatedJpeg(Attributes attrs, int frameIndex,
                                          Double windowCenter, Double windowWidth) throws Exception {
        Object value = attrs.getValue(Tag.PixelData);
        if (!(value instanceof Fragments fragments) || fragments.size() < 2) {
            throw new IllegalStateException("No encapsulated pixel fragments");
        }
        byte[] jpegBytes = fragmentBytes(fragments, frameIndex);
        if (jpegBytes.length < 2 || (jpegBytes[0] & 0xff) != 0xff || (jpegBytes[1] & 0xff) != 0xd8) {
            throw new IllegalStateException("Fragment is not JPEG (missing SOI)");
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        if (decoded == null) {
            throw new IllegalStateException("JDK ImageIO could not decode JPEG fragment");
        }
        if (windowCenter != null && windowWidth != null && windowWidth > 0
                && decoded.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            decoded = applyWindowLevelGray(decoded, windowCenter, windowWidth);
        }
        return encodeJpeg(decoded);
    }

    private byte[] renderRleFrame(Attributes attrs, int frameIndex, String photometric) throws Exception {
        Object value = attrs.getValue(Tag.PixelData);
        if (!(value instanceof Fragments fragments) || fragments.size() < 2) {
            throw new IllegalStateException("No RLE fragments");
        }
        int rows = attrs.getInt(Tag.Rows, 0);
        int cols = attrs.getInt(Tag.Columns, 0);
        int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 8);
        int samples = attrs.getInt(Tag.SamplesPerPixel, 1);
        if (rows <= 0 || cols <= 0 || bitsAllocated != 8) {
            throw new IllegalStateException("Unsupported RLE geometry rows=" + rows + " cols=" + cols
                    + " bits=" + bitsAllocated);
        }
        byte[] compressed = fragmentBytes(fragments, frameIndex);
        int bytesPerSample = 1;
        int frameLen = rows * cols * samples * bytesPerSample;
        byte[] raw = decodeRleFrame(compressed, frameLen, samples);
        return pixelsToJpeg(attrs, raw, rows, cols, samples, photometric, null, null);
    }

    private byte[] renderUncompressedFrame(Attributes attrs, int frameIndex, String photometric,
                                           Double windowCenter, Double windowWidth) throws Exception {
        int rows = attrs.getInt(Tag.Rows, 0);
        int cols = attrs.getInt(Tag.Columns, 0);
        int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 8);
        int samples = attrs.getInt(Tag.SamplesPerPixel, 1);
        int frames = Math.max(1, attrs.getInt(Tag.NumberOfFrames, 1));
        int planar = attrs.getInt(Tag.PlanarConfiguration, 0);
        byte[] pixels = attrs.getBytes(Tag.PixelData);
        if (rows <= 0 || cols <= 0 || pixels == null) {
            throw new IllegalStateException("Unsupported pixel data (rows=" + rows + " cols=" + cols
                    + " bits=" + bitsAllocated + " pixels=" + (pixels == null ? "null" : pixels.length) + ")");
        }
        int bytesPerSample = bitsAllocated <= 8 ? 1 : 2;
        int frameLen;
        if ("YBR_FULL_422".equals(photometric)) {
            frameLen = rows * cols * 2; // 4:2:2 subsampled
        } else {
            frameLen = rows * cols * samples * bytesPerSample;
        }
        int index = Math.min(Math.max(frameIndex, 0), frames - 1);
        int offset = index * frameLen;
        if (offset + frameLen > pixels.length) {
            throw new IllegalStateException("Frame " + (index + 1) + " out of range (pixel bytes="
                    + pixels.length + " need=" + (offset + frameLen) + ")");
        }
        byte[] frame = new byte[frameLen];
        System.arraycopy(pixels, offset, frame, 0, frameLen);
        if (planar == 1 && samples == 3 && !"YBR_FULL_422".equals(photometric)) {
            frame = planarToPixelInterleaved(frame, rows, cols, samples);
        }
        return pixelsToJpeg(attrs, frame, rows, cols, samples, photometric, windowCenter, windowWidth);
    }

    private byte[] pixelsToJpeg(Attributes attrs, byte[] frame, int rows, int cols, int samples,
                                String photometric, Double windowCenter, Double windowWidth)
            throws Exception {
        BufferedImage image;
        switch (photometric) {
            case "RGB" -> image = rgbToImage(frame, rows, cols);
            case "YBR_FULL" -> image = ybrFullToImage(frame, rows, cols);
            case "YBR_FULL_422" -> image = ybrFull422ToImage(frame, rows, cols);
            case "PALETTE COLOR" -> image = paletteToImage(attrs, frame, rows, cols);
            case "MONOCHROME1" -> {
                image = grayToImage(attrs, frame, rows, cols, windowCenter, windowWidth);
                invertGray(image);
            }
            default -> // MONOCHROME2 and unknown single-sample
                    image = grayToImage(attrs, frame, rows, cols, windowCenter, windowWidth);
        }
        return encodeJpeg(image);
    }

    /**
     * Map stored pixel values → 8-bit gray using VOI LUT.
     * Prefer query W/L, else DICOM WindowCenter/Width, else auto from rescaled min/max.
     * Applies RescaleSlope/Intercept and respects signed PixelRepresentation.
     */
    private static BufferedImage grayToImage(Attributes attrs, byte[] pixels, int rows, int cols,
                                             Double windowCenter, Double windowWidth) {
        int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 8);
        int pixelRep = attrs.getInt(Tag.PixelRepresentation, 0);
        double slope = attrs.containsValue(Tag.RescaleSlope) ? attrs.getDouble(Tag.RescaleSlope, 1.0) : 1.0;
        double intercept = attrs.containsValue(Tag.RescaleIntercept)
                ? attrs.getDouble(Tag.RescaleIntercept, 0.0) : 0.0;

        int count = rows * cols;
        double[] values = new double[count];
        if (bitsAllocated <= 8 && pixels.length >= count) {
            for (int i = 0; i < count; i++) {
                int raw = pixels[i] & 0xff;
                values[i] = raw * slope + intercept;
            }
        } else if (bitsAllocated == 16 && pixels.length >= count * 2) {
            for (int i = 0; i < count; i++) {
                int raw = (pixels[i * 2] & 0xff) | ((pixels[i * 2 + 1] & 0xff) << 8);
                if (pixelRep == 1) {
                    raw = (short) raw; // signed 16-bit
                }
                values[i] = raw * slope + intercept;
            }
        } else {
            throw new IllegalStateException("Unsupported gray pixel layout bits=" + bitsAllocated
                    + " len=" + pixels.length);
        }

        double[] voi = resolveVoiWindow(attrs, values, windowCenter, windowWidth);
        double wc = voi[0];
        double ww = voi[1];
        double low = wc - ww / 2.0;
        double high = wc + ww / 2.0;

        BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
        byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < count; i++) {
            double v = values[i];
            if (v <= low) out[i] = 0;
            else if (v >= high) out[i] = (byte) 255;
            else out[i] = (byte) Math.round(((v - low) / (high - low)) * 255.0);
        }
        return image;
    }

    /** Returns [windowCenter, windowWidth]. Never uses CT soft-tissue defaults. */
    private static double[] resolveVoiWindow(Attributes attrs, double[] values,
                                             Double windowCenter, Double windowWidth) {
        if (windowCenter != null && windowWidth != null && windowWidth > 0) {
            return new double[]{windowCenter, windowWidth};
        }
        Double dicomWc = firstDicomDouble(attrs, Tag.WindowCenter);
        Double dicomWw = firstDicomDouble(attrs, Tag.WindowWidth);
        if (dicomWc != null && dicomWw != null && dicomWw > 0) {
            return new double[]{dicomWc, dicomWw};
        }
        // Auto-window from pixel range (percentile-ish: skip extreme 0.5% tails via min/max sample)
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (!(min < max)) {
            return new double[]{min, 1.0};
        }
        double ww = max - min;
        double wc = (min + max) / 2.0;
        return new double[]{wc, Math.max(1.0, ww)};
    }

    private static Double firstDicomDouble(Attributes attrs, int tag) {
        if (!attrs.containsValue(tag)) return null;
        try {
            double[] arr = attrs.getDoubles(tag);
            if (arr != null && arr.length > 0 && !Double.isNaN(arr[0])) {
                return arr[0];
            }
        } catch (Exception ignored) {
            // fall through
        }
        String s = attrs.getString(tag);
        if (s == null || s.isBlank()) return null;
        String first = s.split("\\\\")[0].trim();
        try {
            return Double.parseDouble(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void applyWindowLevel(byte[] src, byte[] dest, int count, int stride,
                                         double windowCenter, double windowWidth) {
        double low = windowCenter - windowWidth / 2.0;
        double high = windowCenter + windowWidth / 2.0;
        for (int i = 0; i < count; i++) {
            int v = src[i * stride] & 0xff;
            if (v <= low) dest[i] = 0;
            else if (v >= high) dest[i] = (byte) 255;
            else dest[i] = (byte) Math.round(((v - low) / (high - low)) * 255.0);
        }
    }

    private static void invertGray(BufferedImage image) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (255 - (data[i] & 0xff));
        }
    }

    private static BufferedImage rgbToImage(byte[] pixels, int rows, int cols) {
        if (pixels.length < rows * cols * 3) {
            throw new IllegalStateException("RGB frame too short: " + pixels.length);
        }
        BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_3BYTE_BGR);
        byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0, j = 0; i < rows * cols; i++, j += 3) {
            out[j] = pixels[i * 3 + 2];
            out[j + 1] = pixels[i * 3 + 1];
            out[j + 2] = pixels[i * 3];
        }
        return image;
    }

    private static BufferedImage ybrFullToImage(byte[] pixels, int rows, int cols) {
        if (pixels.length < rows * cols * 3) {
            throw new IllegalStateException("YBR_FULL frame too short: " + pixels.length);
        }
        BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_3BYTE_BGR);
        byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < rows * cols; i++) {
            int y = pixels[i * 3] & 0xff;
            int cb = (pixels[i * 3 + 1] & 0xff) - 128;
            int cr = (pixels[i * 3 + 2] & 0xff) - 128;
            int r = clamp(Math.round(y + 1.402f * cr));
            int g = clamp(Math.round(y - 0.344136f * cb - 0.714136f * cr));
            int b = clamp(Math.round(y + 1.772f * cb));
            int j = i * 3;
            out[j] = (byte) b;
            out[j + 1] = (byte) g;
            out[j + 2] = (byte) r;
        }
        return image;
    }

    private static BufferedImage ybrFull422ToImage(byte[] pixels, int rows, int cols) {
        // Stored as Y1 Y2 Cb Cr per 2 pixels (DICOM YBR_FULL_422).
        int expected = rows * cols * 2;
        if (pixels.length < expected) {
            throw new IllegalStateException("YBR_FULL_422 frame too short: " + pixels.length);
        }
        BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_3BYTE_BGR);
        byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int src = 0;
        for (int i = 0; i < rows * cols; i += 2) {
            int y1 = pixels[src++] & 0xff;
            int y2 = pixels[src++] & 0xff;
            int cb = (pixels[src++] & 0xff) - 128;
            int cr = (pixels[src++] & 0xff) - 128;
            writeYbrPixel(out, i, y1, cb, cr);
            if (i + 1 < rows * cols) {
                writeYbrPixel(out, i + 1, y2, cb, cr);
            }
        }
        return image;
    }

    private static void writeYbrPixel(byte[] out, int pixelIndex, int y, int cb, int cr) {
        int r = clamp(Math.round(y + 1.402f * cr));
        int g = clamp(Math.round(y - 0.344136f * cb - 0.714136f * cr));
        int b = clamp(Math.round(y + 1.772f * cb));
        int j = pixelIndex * 3;
        out[j] = (byte) b;
        out[j + 1] = (byte) g;
        out[j + 2] = (byte) r;
    }

    private static BufferedImage paletteToImage(Attributes attrs, byte[] indexes, int rows, int cols) {
        byte[] r = expandPalette(attrs, Tag.RedPaletteColorLookupTableDescriptor,
                Tag.RedPaletteColorLookupTableData);
        byte[] g = expandPalette(attrs, Tag.GreenPaletteColorLookupTableDescriptor,
                Tag.GreenPaletteColorLookupTableData);
        byte[] b = expandPalette(attrs, Tag.BluePaletteColorLookupTableDescriptor,
                Tag.BluePaletteColorLookupTableData);
        IndexColorModel cm = new IndexColorModel(8, 256, r, g, b);
        BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_INDEXED, cm);
        byte[] out = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(indexes, 0, out, 0, Math.min(out.length, indexes.length));
        return image;
    }

    private static byte[] expandPalette(Attributes attrs, int descTag, int dataTag) {
        int[] desc = attrs.getInts(descTag);
        byte[] data = attrs.getSafeBytes(dataTag);
        byte[] out = new byte[256];
        if (desc == null || data == null || desc.length < 3) {
            for (int i = 0; i < 256; i++) out[i] = (byte) i;
            return out;
        }
        int nEntries = desc[0] == 0 ? 65536 : desc[0];
        int first = desc[1];
        int bits = desc[2];
        nEntries = Math.min(nEntries, 256);
        if (bits > 8) {
            // 16-bit LUT entries — use high byte (common US convention).
            for (int i = 0; i < nEntries; i++) {
                int src = i * 2;
                if (src + 1 >= data.length) break;
                int idx = first + i;
                if (idx >= 0 && idx < 256) {
                    out[idx] = data[src + 1]; // big-endian high byte; dcm4che stores OW as LE often
                    // Prefer the more significant byte:
                    int lo = data[src] & 0xff;
                    int hi = data[src + 1] & 0xff;
                    out[idx] = (byte) (hi != 0 ? hi : lo);
                }
            }
        } else {
            for (int i = 0; i < nEntries && i < data.length; i++) {
                int idx = first + i;
                if (idx >= 0 && idx < 256) out[idx] = data[i];
            }
        }
        return out;
    }

    private static byte[] planarToPixelInterleaved(byte[] planar, int rows, int cols, int samples) {
        int plane = rows * cols;
        byte[] interleaved = new byte[planar.length];
        for (int i = 0; i < plane; i++) {
            for (int s = 0; s < samples; s++) {
                interleaved[i * samples + s] = planar[s * plane + i];
            }
        }
        return interleaved;
    }

    private static byte[] fragmentBytes(Fragments fragments, int frameIndex) {
        int index = Math.min(Math.max(frameIndex, 0), fragments.size() - 2);
        Object frag = fragments.get(index + 1); // index 0 is basic offset table
        if (!(frag instanceof byte[] bytes) || bytes.length == 0) {
            throw new IllegalStateException("Empty pixel fragment at frame " + (frameIndex + 1));
        }
        return bytes;
    }

    /**
     * DICOM RLE Lossless (PS3.5 Annex G) — header of 16 UL offsets, then PackBits-like segments.
     */
    static byte[] decodeRleFrame(byte[] compressed, int expectedLength, int samples) {
        if (compressed.length < 64) {
            throw new IllegalStateException("RLE header too short");
        }
        int[] offsets = new int[16];
        for (int i = 0; i < 16; i++) {
            offsets[i] = (compressed[i * 4] & 0xff)
                    | ((compressed[i * 4 + 1] & 0xff) << 8)
                    | ((compressed[i * 4 + 2] & 0xff) << 16)
                    | ((compressed[i * 4 + 3] & 0xff) << 24);
        }
        int numSegments = offsets[0];
        if (numSegments < 1 || numSegments > 15) {
            throw new IllegalStateException("Invalid RLE segment count: " + numSegments);
        }
        int planeSize = expectedLength / Math.max(1, samples);
        if (numSegments == 1) {
            return decodeRleSegment(compressed, offsets[1],
                    segmentEnd(offsets, 1, numSegments, compressed.length), expectedLength);
        }
        // Multi-segment: typically one segment per sample (or per byte of 16-bit).
        byte[][] planes = new byte[numSegments][];
        for (int s = 0; s < numSegments; s++) {
            int start = offsets[s + 1];
            int end = segmentEnd(offsets, s + 1, numSegments, compressed.length);
            int segLen = (numSegments == samples) ? planeSize : (expectedLength / numSegments);
            planes[s] = decodeRleSegment(compressed, start, end, segLen);
        }
        if (numSegments == samples) {
            byte[] out = new byte[expectedLength];
            for (int i = 0; i < planeSize; i++) {
                for (int s = 0; s < samples; s++) {
                    out[i * samples + s] = planes[s][i];
                }
            }
            return out;
        }
        // 16-bit: high/low byte segments interleaved
        byte[] out = new byte[expectedLength];
        int pixels = expectedLength / numSegments;
        for (int i = 0; i < pixels; i++) {
            for (int s = 0; s < numSegments; s++) {
                out[i * numSegments + s] = planes[s][i];
            }
        }
        return out;
    }

    private static int segmentEnd(int[] offsets, int segmentIndex, int numSegments, int length) {
        if (segmentIndex < numSegments) {
            int next = offsets[segmentIndex + 1];
            if (next > offsets[segmentIndex] && next <= length) return next;
        }
        return length;
    }

    private static byte[] decodeRleSegment(byte[] src, int start, int end, int expected) {
        byte[] out = new byte[expected];
        int i = start;
        int o = 0;
        while (i < end && o < expected) {
            int n = src[i++];
            if (n >= 0 && n <= 127) {
                int count = n + 1;
                for (int k = 0; k < count && i < end && o < expected; k++) {
                    out[o++] = src[i++];
                }
            } else if (n >= -127 && n <= -1) {
                int count = 1 - n;
                if (i >= end) break;
                byte v = src[i++];
                for (int k = 0; k < count && o < expected; k++) {
                    out[o++] = v;
                }
            }
            // -128 is noop
        }
        if (o < expected) {
            throw new IllegalStateException("RLE segment underfilled: got=" + o + " expected=" + expected);
        }
        return out;
    }

    private static String normalizePhotometric(String raw) {
        if (raw == null || raw.isBlank()) return "MONOCHROME2";
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static int clamp(float v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return (int) v;
    }

    private static BufferedImage applyWindowLevelGray(BufferedImage src, double windowCenter, double windowWidth) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = ((DataBufferByte) src.getRaster().getDataBuffer()).getData();
        byte[] dest = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        applyWindowLevel(pixels, dest, dest.length, 1, windowCenter, windowWidth);
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws Exception {
        BufferedImage writable = toJpegCompatible(image);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(writable, "jpeg", baos) || baos.size() == 0) {
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            baos.reset();
            if (!ImageIO.write(rgb, "jpeg", baos) || baos.size() == 0) {
                throw new IllegalStateException("ImageIO failed to encode JPEG");
            }
        }
        return baos.toByteArray();
    }

    private static BufferedImage toJpegCompatible(BufferedImage image) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_BYTE_GRAY
                || type == BufferedImage.TYPE_3BYTE_BGR
                || type == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        // Palette / custom ColorModels (e.g. dcm4che PaletteColorModel) need an RGB raster.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static final class UnsupportedTransferSyntaxException extends Exception {
        UnsupportedTransferSyntaxException(String message) {
            super(message);
        }
    }
}
