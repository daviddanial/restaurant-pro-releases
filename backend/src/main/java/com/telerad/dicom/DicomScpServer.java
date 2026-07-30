package com.telerad.dicom;

import com.telerad.config.TeleradProperties;
import com.telerad.ingest.IngestPublisher;
import com.telerad.storage.ObjectStorageService;
import com.telerad.tenant.DicomNode;
import com.telerad.tenant.DicomNodeRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Component
@ConditionalOnProperty(prefix = "telerad.scp", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DicomScpServer {
    private final TeleradProperties properties;
    private final DicomNodeRepository dicomNodeRepository;
    private final ObjectStorageService storage;
    private final IngestPublisher ingestPublisher;

    private Device device;
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @PostConstruct
    public void start() throws Exception {
        String aeTitle = properties.getScp().getAeTitle();
        int port = properties.getScp().getPort();

        device = new Device("telerad-scp");
        ApplicationEntity ae = new ApplicationEntity(aeTitle);
        Connection conn = new Connection();
        conn.setHostname(properties.getScp().getBindAddress());
        conn.setPort(port);

        device.addConnection(conn);
        device.addApplicationEntity(ae);
        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);

        DicomServiceRegistry services = new DicomServiceRegistry();
        services.addDicomService(new BasicCEchoSCP());
        services.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq,
                                 PDVInputStream data, Attributes rsp) throws java.io.IOException {
                String callingAe = as.getCallingAET();
                Optional<DicomNode> node = dicomNodeRepository.findByAeTitleIgnoreCaseAndActiveTrue(callingAe);
                if (node.isEmpty()) {
                    log.warn("Rejecting C-STORE from unknown AE {}", callingAe);
                    rsp.setInt(Tag.Status, VR.US, Status.NotAuthorized);
                    return;
                }
                DicomNode dicomNode = node.get();
                if (dicomNode.getAllowedIp() != null && !dicomNode.getAllowedIp().isBlank()) {
                    String remote = as.getSocket().getInetAddress().getHostAddress();
                    if (!dicomNode.getAllowedIp().equals(remote)) {
                        log.warn("Rejecting C-STORE from AE {} IP {} (allowed {})", callingAe, remote, dicomNode.getAllowedIp());
                        rsp.setInt(Tag.Status, VR.US, Status.NotAuthorized);
                        return;
                    }
                }

                String cuid = rq.getString(Tag.AffectedSOPClassUID);
                String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
                String ts = pc.getTransferSyntax();

                Attributes fmi = as.createFileMetaInformation(iuid, cuid, ts);
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                try (DicomOutputStream dos = new DicomOutputStream(bout, UID.ExplicitVRLittleEndian)) {
                    dos.writeFileMetaInformation(fmi);
                    data.copyTo(dos);
                }
                byte[] bytes = bout.toByteArray();

                // Parse study/series UIDs from dataset portion
                Attributes dataset;
                try (org.dcm4che3.io.DicomInputStream dis =
                             new org.dcm4che3.io.DicomInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    dataset = dis.readDataset(-1, -1);
                } catch (Exception e) {
                    log.error("Failed to parse C-STORE dataset: {}", e.getMessage());
                    rsp.setInt(Tag.Status, VR.US, Status.ProcessingFailure);
                    return;
                }

                String studyUid = dataset.getString(Tag.StudyInstanceUID, "unknown");
                String seriesUid = dataset.getString(Tag.SeriesInstanceUID, "unknown");
                String sopUid = dataset.getString(Tag.SOPInstanceUID, iuid);
                String key = dicomNode.getTenantId() + "/" + studyUid + "/" + seriesUid + "/" + sopUid + ".dcm";
                storage.put(key, bytes, "application/dicom");
                ingestPublisher.publish(dicomNode.getTenantId(), key, studyUid, "CSTORE", callingAe);
                log.info("C-STORE accepted from {} sop={} key={}", callingAe, sopUid, key);
                rsp.setInt(Tag.Status, VR.US, Status.Success);
            }
        });
        ae.setDimseRQHandler(services);

        // Accept common storage SOP classes with Explicit VR LE
        for (String storageUid : commonStorageSopClasses()) {
            ae.addTransferCapability(new TransferCapability(null, storageUid,
                    TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian));
        }
        ae.addTransferCapability(new TransferCapability(null, UID.Verification,
                TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian));

        executor = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduledExecutor);
        device.bindConnections();
        log.info("DICOM SCP listening on {}:{} AE={}", properties.getScp().getBindAddress(), port, aeTitle);
    }

    @PreDestroy
    public void stop() {
        try {
            if (device != null) device.unbindConnections();
        } catch (Exception e) {
            log.warn("Error stopping SCP: {}", e.getMessage());
        }
        if (executor != null) executor.shutdownNow();
        if (scheduledExecutor != null) scheduledExecutor.shutdownNow();
    }

    private static String[] commonStorageSopClasses() {
        return new String[]{
                UID.CTImageStorage,
                UID.MRImageStorage,
                UID.ComputedRadiographyImageStorage,
                UID.DigitalXRayImageStorageForPresentation,
                UID.DigitalXRayImageStorageForProcessing,
                UID.UltrasoundImageStorage,
                UID.UltrasoundMultiFrameImageStorage,
                UID.SecondaryCaptureImageStorage,
                UID.EnhancedCTImageStorage,
                UID.EnhancedMRImageStorage,
                UID.PositronEmissionTomographyImageStorage,
                UID.NuclearMedicineImageStorage,
                UID.XRayAngiographicImageStorage,
                UID.XRayRadiofluoroscopicImageStorage
        };
    }
}
