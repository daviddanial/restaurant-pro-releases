package com.telerad.order;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyPriority;
import com.telerad.common.StudyStatus;
import com.telerad.ingest.IngestWorker;
import com.telerad.study.Patient;
import com.telerad.study.PatientRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Orders and scanner workflow (KB note 15): reception creates orders, images are matched to them
 * when they arrive, and the order status follows the scan, the read and the report.
 *
 * <p>Rules that hold everywhere: a status never goes backwards (late or repeated events only add
 * history), and a study that does not clearly belong to an order waits for a person.
 */
@Service
@Slf4j
public class OrderService {
    static final EnumSet<OrderStatus> OPEN = EnumSet.of(OrderStatus.SCHEDULED, OrderStatus.ARRIVED, OrderStatus.SCANNING);
    static final LocalDate PATIENT_COUNTER_DAY = LocalDate.of(1970, 1, 1);
    static final int LINKABLE_DAYS = 7;
    private static final DateTimeFormatter ACC_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final ImagingOrderRepository orders;
    private final OrderEventRepository events;
    private final PatientRepository patients;
    private final StudyRepository studies;
    private final TenantRepository tenants;
    private final AuditService auditService;
    private final ProcedureService procedureService;
    private final IngestWorker ingestWorker;
    private final TransactionTemplate tx;
    private final ZoneId facilityZone;
    Clock clock = Clock.systemUTC();

    public OrderService(ImagingOrderRepository orders, OrderEventRepository events, PatientRepository patients,
                        StudyRepository studies, TenantRepository tenants, AuditService auditService,
                        ProcedureService procedureService, @Lazy IngestWorker ingestWorker, TransactionTemplate tx,
                        @Value("${telerad.images.facility-zone:Africa/Addis_Ababa}") String facilityZone) {
        this.orders = orders;
        this.events = events;
        this.patients = patients;
        this.studies = studies;
        this.tenants = tenants;
        this.auditService = auditService;
        this.procedureService = procedureService;
        this.ingestWorker = ingestWorker;
        this.tx = tx;
        this.facilityZone = ZoneId.of(facilityZone);
    }

    // ================================================================== DTOs

    public record PatientDto(UUID id, String mrn, String name, LocalDate birthDate, String sex) {}

    public record CreateOrder(UUID tenantId, String patientMrn, String patientName, LocalDate birthDate,
                              String sex, String modality, String procedureDescription, StudyPriority priority,
                              String referringPhysician, String clinicalInfo, LocalDate scheduledDate,
                              Laterality laterality, String additionalInstructions, UUID procedureId) {}

    public record OrderDto(
            UUID id, UUID tenantId, String hospitalName, String accessionNumber, PatientDto patient,
            String modality, UUID procedureId, String procedureDescription, Laterality laterality, String additionalInstructions,
            StudyPriority priority, String referringPhysician, String clinicalInfo, OrderSource source,
            /** Where the order is now, including the read and the report. */
            OrderStatus status,
            LocalDate scheduledDate, UUID studyId, int imageCount,
            Instant arrivedAt, Instant scanningAt, Instant scannedAt, Instant readyAt,
            Instant mrtConfirmedAt, Instant cancelledAt, String cancelReason, Instant createdAt,
            /** Scanned, but the facility waits for the MRT's "Scan complete". */
            boolean waitingForMrt,
            boolean nameDiffers, String scannerPatientName, boolean imagesAfterReport) {}

    public record EventDto(Instant at, String kind, String detail, UUID userId) {}

    public record OrderDetail(OrderDto order, List<EventDto> history) {}

    public record ScannerData(String accessionNumber, String patientId, String patientName, LocalDate birthDate,
                              String modality, LocalDate studyDate) {}

    public record ReconcileItem(UUID studyId, String studyInstanceUid, MatchState matchState, String matchNote,
                                String scannerAccession, String scannerPatientId, String scannerPatientName,
                                LocalDate scannerBirthDate, String modality, String studyDescription,
                                LocalDate studyDate, int imageCount, Instant receivedAt,
                                OrderDto suggested, List<OrderDto> candidates) {}

    // ================================================================== reception / MRT actions

    @Transactional
    public OrderDto create(CreateOrder req) {
        UserPrincipal user = UserPrincipal.current();
        UUID tenantId = tenantFor(user, req.tenantId());
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hospital not found"));
        if (blank(req.patientName())) throw bad("Patient name is required");
        if (blank(req.modality())) throw bad("Modality is required");
        String modality = req.modality().trim().toUpperCase(Locale.ROOT);
        if (!modality.matches("[A-Z]{2,4}")) throw bad("Modality must be a DICOM code such as CT, MR, CR, US");
        // From the central list when given (the reception screen always sends one); free text stays
        // possible for orders from other systems until their procedures are mapped.
        Procedure procedure = req.procedureId() == null ? null : procedureService.requireOrderable(req.procedureId(), modality);
        if (req.sex() != null && !req.sex().isBlank() && !req.sex().matches("[MFO]")) throw bad("Sex must be M, F or O");
        LocalDate today = today();
        LocalDate day = req.scheduledDate() == null ? today : req.scheduledDate();
        if (day.isBefore(today.minusDays(1))) throw bad("Scheduled date is in the past");
        if (req.additionalInstructions() != null && req.additionalInstructions().length() > 2000) {
            throw bad("Additional instructions are too long (max 2000 characters)");
        }

        String mrn = blank(req.patientMrn()) ? generatePatientId(tenant) : req.patientMrn().trim();
        if (mrn.length() > 64) throw bad("Patient ID is too long (max 64)");
        Patient patient = patients.findByTenantIdAndPatientId(tenantId, mrn).orElseGet(() -> Patient.builder()
                .tenantId(tenantId).patientId(mrn).build());
        patient.setPatientName(req.patientName().trim());
        patient.setBirthDate(req.birthDate());
        patient.setSex(blank(req.sex()) ? null : req.sex());
        patient.setRegistered(true);
        patient = patients.save(patient);

        ImagingOrder order = ImagingOrder.builder()
                .tenantId(tenantId)
                .accessionNumber(nextAccession(tenant, today))
                .patientId(patient.getId())
                .modality(modality)
                .procedureId(procedure == null ? null : procedure.getId())
                .procedureDescription(procedure != null ? procedure.getName() : trimToNull(req.procedureDescription()))
                .laterality(req.laterality() == null ? Laterality.NOT_APPLICABLE : req.laterality())
                .additionalInstructions(trimToNull(req.additionalInstructions()))
                .priority(req.priority() == null ? StudyPriority.ROUTINE : req.priority())
                .referringPhysician(trimToNull(req.referringPhysician()))
                .clinicalInfo(trimToNull(req.clinicalInfo()))
                .source(OrderSource.MANUAL)
                .status(OrderStatus.SCHEDULED)
                .scheduledDate(day)
                .createdBy(user.getId())
                .build();
        order = orders.save(order);
        event(order, user.getId(), "CREATED", "Order created by reception (" + order.getAccessionNumber() + ")");
        auditService.record(tenantId, user.getId(), "ORDER_CREATE", "ORDER", order.getId().toString(),
                order.getAccessionNumber(), null);
        return toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> list(UUID tenantId, LocalDate day) {
        UUID tid = tenantFor(UserPrincipal.current(), tenantId);
        return orders.findByTenantIdAndScheduledDateOrderByCreatedAtAsc(tid, day == null ? today() : day)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(UUID id) {
        ImagingOrder o = requireOrder(id);
        return new OrderDetail(toDto(o), events.findByOrderIdOrderByAtAsc(o.getId()).stream()
                .map(e -> new EventDto(e.getAt(), e.getKind(), e.getDetail(), e.getUserId())).toList());
    }

    @Transactional(readOnly = true)
    public Optional<PatientDto> findPatient(UUID tenantId, String mrn) {
        UUID tid = tenantFor(UserPrincipal.current(), tenantId);
        if (blank(mrn)) return Optional.empty();
        return patients.findByTenantIdAndPatientId(tid, mrn.trim())
                .map(p -> new PatientDto(p.getId(), p.getPatientId(), p.getPatientName(), p.getBirthDate(), p.getSex()));
    }

    @Transactional
    public OrderDto arrived(UUID id) {
        ImagingOrder o = requireOrder(id);
        requireNotClosed(o);
        if (!advance(o, OrderStatus.ARRIVED, UserPrincipal.current().getId(), "Reception: patient arrived")) {
            event(o, UserPrincipal.current().getId(), "LATE_EVENT", "\"Patient arrived\" clicked when already " + o.getStatus());
        }
        return toDto(o);
    }

    @Transactional
    public OrderDto startScan(UUID id) {
        ImagingOrder o = requireOrder(id);
        requireNotClosed(o);
        if (!advance(o, OrderStatus.SCANNING, UserPrincipal.current().getId(), "MRT: start scan")) {
            event(o, UserPrincipal.current().getId(), "LATE_EVENT", "\"Start scan\" clicked when already " + o.getStatus());
        }
        return toDto(o);
    }

    /**
     * "Scan complete" from the MRT. It only moves the order forward; when the order is already past
     * Scanned (e.g. it went to the radiologists by itself) it is recorded and nothing else changes.
     */
    @Transactional
    public OrderDto scanComplete(UUID id) {
        UUID userId = UserPrincipal.current().getId();
        ImagingOrder o = requireOrder(id);
        requireNotClosed(o);
        OrderStatus before = effectiveStatus(o);
        if (o.getMrtConfirmedAt() == null) {
            o.setMrtConfirmedAt(now());
            o.setMrtConfirmedBy(userId);
        }
        if (o.getStudyId() == null) {
            orders.save(o);
            event(o, userId, "MRT_CONFIRMED", "Scan complete clicked before any image arrived: "
                    + "the study goes on when its images arrive");
        } else if (o.getStatus().isBefore(OrderStatus.SCANNED)) {
            advance(o, OrderStatus.SCANNED, userId, "MRT: scan complete");
        } else if (o.getStatus() == OrderStatus.SCANNED) {
            orders.save(o);
            event(o, userId, "MRT_CONFIRMED", "MRT confirmed the scan");
            maybeReady(o);
        } else {
            orders.save(o);
            event(o, userId, "LATE_EVENT", "\"Scan complete\" clicked when already " + before
                    + ": recorded, status unchanged");
        }
        return toDto(o);
    }

    @Transactional
    public OrderDto cancel(UUID id, String reason) {
        ImagingOrder o = requireOrder(id);
        requireNotClosed(o);
        if (o.getStudyId() != null || !o.getStatus().isBefore(OrderStatus.SCANNING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The scan has started: the order can no longer be cancelled");
        }
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(now());
        o.setCancelReason(trimToNull(reason));
        orders.save(o);
        UUID userId = UserPrincipal.current().getId();
        event(o, userId, "CANCELLED", reason == null ? "Cancelled" : "Cancelled: " + reason.trim());
        auditService.record(o.getTenantId(), userId, "ORDER_CANCEL", "ORDER", o.getId().toString(), reason, null);
        return toDto(o);
    }

    @Transactional
    public OrderDto noShow(UUID id) {
        ImagingOrder o = requireOrder(id);
        requireNotClosed(o);
        if (o.getStudyId() != null || !o.getStatus().isBefore(OrderStatus.SCANNING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The scan has started: not a no-show");
        }
        o.setStatus(OrderStatus.NO_SHOW);
        o.setCancelledAt(now());
        orders.save(o);
        event(o, UserPrincipal.current().getId(), "NO_SHOW", "Patient did not come");
        return toDto(o);
    }

    // ================================================================== images arrive (ingest hooks)

    /**
     * Called for every new image, inside the ingest transaction, after the study row is saved.
     *
     * @param newStudy the study row was created by this image
     * @return whether the study may become ready to read
     */
    @Transactional
    public boolean onImageReceived(Study study, ScannerData scanner, boolean newStudy) {
        Tenant tenant = tenants.findById(study.getTenantId()).orElse(null);
        if (tenant == null || !tenant.isOrdersEnabled()) return true;
        if (study.getMatchState() == null && !newStudy) return true; // older than orders at this facility

        study.setLastImageAt(now());
        if (study.getMatchState() == null) {
            study.setScannerPatientId(trimToNull(scanner.patientId()));
            study.setScannerPatientName(trimToNull(scanner.patientName()));
            study.setScannerBirthDate(scanner.birthDate());
            match(study, tenant, scanner);
        }
        ImagingOrder order = linkedOrder(study);
        if (order != null) {
            advance(order, OrderStatus.SCANNING, null, "First image received");
            if (study.getStatus() == StudyStatus.REPORTED || study.getStatus() == StudyStatus.DELIVERED) {
                event(order, null, "IMAGES_AFTER_REPORT", "New image after the report was signed: check, addendum if needed");
            }
        }
        studies.save(study);
        return readyAllowed(study, order);
    }

    /** Whether a study may become ready to read (re-uploads, and checks after linking). */
    @Transactional(readOnly = true)
    public boolean readyAllowed(Study study) {
        if (study.getMatchState() == null) return true;
        return readyAllowed(study, linkedOrder(study));
    }

    private boolean readyAllowed(Study study, ImagingOrder order) {
        MatchState m = study.getMatchState();
        if (m == null || m == MatchState.RELEASED) return true;
        if (m.isHeld()) return false;
        return order != null && order.getStatus().rank() >= OrderStatus.READY.rank();
    }

    /** The hospital server says the study is complete (its last index batch). */
    @Transactional
    public void onStudyComplete(UUID tenantId, String studyInstanceUid) {
        studies.findByTenantIdAndStudyInstanceUid(tenantId, studyInstanceUid)
                .flatMap(s -> orders.findByStudyId(s.getId()))
                .ifPresent(o -> advance(o, OrderStatus.SCANNED, null, "Hospital server: all images received"));
    }

    /** Orders whose images stopped arriving: the scan is finished. */
    @Scheduled(fixedDelayString = "${telerad.orders.stable-check-ms:15000}", initialDelayString = "${telerad.orders.stable-check-ms:15000}")
    public void checkStableScans() {
        for (ImagingOrder candidate : orders.findByStatusAndStudyIdIsNotNull(OrderStatus.SCANNING)) {
            try {
                tx.executeWithoutResult(s -> checkStable(candidate.getId()));
            } catch (RuntimeException e) {
                log.warn("Stable check for order {} failed (retried next run): {}", candidate.getId(), e.getMessage());
            }
        }
    }

    void checkStable(UUID orderId) {
        ImagingOrder o = orders.findById(orderId).orElse(null);
        if (o == null || o.getStatus() != OrderStatus.SCANNING || o.getStudyId() == null) return;
        Tenant t = tenants.findById(o.getTenantId()).orElse(null);
        Study s = studies.findById(o.getStudyId()).orElse(null);
        if (t == null || s == null) return;
        Instant last = s.getLastImageAt() == null ? s.getUpdatedAt() : s.getLastImageAt();
        if (last != null && last.isBefore(now().minusSeconds(t.getScanStableSeconds()))) {
            advance(o, OrderStatus.SCANNED, null, "No new image for " + t.getScanStableSeconds() + " s");
        }
    }

    // ================================================================== reconciliation

    @Transactional(readOnly = true)
    public List<ReconcileItem> reconcileList(UUID tenantId) {
        UUID tid = tenantFor(UserPrincipal.current(), tenantId);
        List<OrderDto> linkable = orders.findLinkable(tid, today().minusDays(LINKABLE_DAYS), OPEN)
                .stream().map(this::toDto).toList();
        List<ReconcileItem> out = new ArrayList<>();
        for (Study s : studies.findByTenantIdAndMatchStateInOrderByCreatedAtAsc(tid,
                List.of(MatchState.NEEDS_CHECK, MatchState.SUGGESTED, MatchState.UNMATCHED))) {
            OrderDto suggested = s.getSuggestedOrderId() == null ? null
                    : orders.findById(s.getSuggestedOrderId()).map(this::toDto).orElse(null);
            out.add(new ReconcileItem(s.getId(), s.getStudyInstanceUid(), s.getMatchState(), s.getMatchNote(),
                    s.getAccessionNumber(), s.getScannerPatientId(), s.getScannerPatientName(),
                    s.getScannerBirthDate(), s.getModality(), s.getStudyDescription(), s.getStudyDate(),
                    s.getInstanceCount(), s.getCreatedAt(), suggested, linkable));
        }
        return out;
    }

    /** A person links a held study to an order ("Link"). */
    @Transactional
    public OrderDto linkByPerson(UUID studyId, UUID orderId) {
        UUID userId = UserPrincipal.current().getId();
        Study s = requireHeldStudy(studyId);
        ImagingOrder o = requireOrder(orderId);
        if (!o.getTenantId().equals(s.getTenantId())) throw bad("Order and study are from different hospitals");
        if (o.getStatus().isClosed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is " + o.getStatus());
        if (o.getStudyId() != null && !o.getStudyId().equals(s.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order already has images");
        }
        Patient op = patients.findById(o.getPatientId()).orElseThrow();
        String scannerId = s.getScannerPatientId();
        String note = "Linked by a person"
                + (scannerId != null && !OrderMatcher.samePatientId(op.getPatientId(), scannerId)
                ? " (patient ID at the scanner was " + scannerId + ")" : "");
        link(s, o, userId, note);
        auditService.record(s.getTenantId(), userId, "ORDER_LINK", "STUDY", s.getId().toString(),
                o.getAccessionNumber() + "; was " + s.getMatchState(), null);
        studies.save(s);
        checkStable(o.getId());
        return toDto(o);
    }

    /** "Not this patient": the suggested order is wrong; the study stays held without a suggestion. */
    @Transactional
    public void rejectSuggestion(UUID studyId) {
        UUID userId = UserPrincipal.current().getId();
        Study s = requireHeldStudy(studyId);
        UUID suggested = s.getSuggestedOrderId();
        s.setMatchState(MatchState.UNMATCHED);
        s.setSuggestedOrderId(null);
        s.setMatchNote("A person marked the suggested order as not this patient");
        studies.save(s);
        auditService.record(s.getTenantId(), userId, "ORDER_MATCH_REJECT", "STUDY", s.getId().toString(),
                suggested == null ? null : suggested.toString(), null);
    }

    /** Read without an order (e.g. emergency, or a study from another source). */
    @Transactional
    public void releaseWithoutOrder(UUID studyId, String reason) {
        UUID userId = UserPrincipal.current().getId();
        Study s = requireHeldStudy(studyId);
        if (blank(reason)) throw bad("A reason is required");
        s.setMatchState(MatchState.RELEASED);
        s.setSuggestedOrderId(null);
        s.setMatchNote("Released without an order: " + reason.trim());
        studies.save(s);
        auditService.record(s.getTenantId(), userId, "ORDER_RELEASE", "STUDY", s.getId().toString(), reason.trim(), null);
        if (s.getStatus() == StudyStatus.RECEIVED) ingestWorker.markReady(s);
    }

    // ================================================================== matching

    void match(Study study, Tenant tenant, ScannerData d) {
        String acc = trimToNull(d.accessionNumber());
        if (acc != null) {
            Optional<ImagingOrder> found = orders.findByTenantIdAndAccessionNumber(tenant.getId(), acc);
            if (found.isPresent()) {
                ImagingOrder o = found.get();
                Patient op = patients.findById(o.getPatientId()).orElseThrow();
                if (o.getStatus().isClosed()) {
                    hold(study, MatchState.NEEDS_CHECK, o, "Order " + acc + " is " + o.getStatus());
                } else if (o.getStudyId() != null && !o.getStudyId().equals(study.getId())) {
                    hold(study, MatchState.NEEDS_CHECK, o, "Order " + acc + " already has images from another study");
                } else if (OrderMatcher.samePatientId(op.getPatientId(), d.patientId())) {
                    link(study, o, null, "Matched automatically: accession " + acc + " and patient ID");
                } else {
                    hold(study, MatchState.NEEDS_CHECK, o, "Patient ID at the scanner (" + nullToDash(d.patientId())
                            + ") differs from order " + acc + " (" + op.getPatientId() + ")");
                }
                return;
            }
        }
        String pid = trimToNull(d.patientId());
        String modality = trimToNull(d.modality());
        if (pid != null && modality != null) {
            Optional<Patient> p = patients.findByTenantIdAndPatientId(tenant.getId(), pid);
            if (p.isPresent()) {
                LocalDate day = d.studyDate() == null ? today() : d.studyDate();
                List<ImagingOrder> c = orders.findOpenCandidates(tenant.getId(), List.of(p.get().getId()),
                        modality.toUpperCase(Locale.ROOT), day, OPEN);
                if (c.size() == 1) {
                    hold(study, MatchState.SUGGESTED, c.get(0), (acc == null ? "No accession number at the scanner"
                            : "Accession " + acc + " is not a known order") + "; patient ID, modality and date fit order "
                            + c.get(0).getAccessionNumber());
                    return;
                }
            }
        }
        hold(study, MatchState.UNMATCHED, null, acc == null
                ? "No accession number at the scanner and no single order fits"
                : "Accession " + acc + " is not a known order");
    }

    private void hold(Study study, MatchState state, ImagingOrder suggested, String note) {
        study.setMatchState(state);
        study.setSuggestedOrderId(suggested == null ? null : suggested.getId());
        study.setMatchNote(note);
        auditService.record(study.getTenantId(), null, "ORDER_MATCH_" + state, "STUDY",
                study.getId() == null ? null : study.getId().toString(), note, null);
    }

    /** Link study and order: the order's patient, accession and priority are used from now on. */
    void link(Study study, ImagingOrder o, UUID userId, String how) {
        Patient op = patients.findById(o.getPatientId()).orElseThrow();
        o.setStudyId(study.getId());
        study.setMatchState(MatchState.LINKED);
        study.setSuggestedOrderId(null);
        study.setPatientId(op.getId());
        study.setAccessionNumber(o.getAccessionNumber());
        if (blank(study.getStudyDescription())) study.setStudyDescription(examName(o));
        if (study.getStatus() == StudyStatus.RECEIVED) study.setPriority(o.getPriority());
        boolean differs = !OrderMatcher.nameMatches(op.getPatientName(), study.getScannerPatientName());
        study.setNameDiffers(differs);
        study.setMatchNote(differs ? "Name at the scanner: " + OrderMatcher.displayName(study.getScannerPatientName())
                + " (the order's name is used)" : null);
        orders.save(o);
        event(o, userId, "LINKED", how + (differs ? "; name at the scanner differs ("
                + OrderMatcher.displayName(study.getScannerPatientName()) + ")" : ""));
        if (study.getLastImageAt() != null) advance(o, OrderStatus.SCANNING, null, "Images linked");
    }

    // ================================================================== status changes

    /**
     * Move the order forward to {@code target}. Returns false (and changes nothing) when the order
     * is already there or further: a status never goes backwards.
     */
    boolean advance(ImagingOrder o, OrderStatus target, UUID userId, String detail) {
        if (!o.getStatus().isBefore(target)) return false;
        Instant now = now();
        o.setStatus(target);
        switch (target) {
            case ARRIVED -> o.setArrivedAt(now);
            case SCANNING -> o.setScanningAt(now);
            case SCANNED -> o.setScannedAt(now);
            case READY -> o.setReadyAt(now);
            default -> { }
        }
        orders.save(o);
        event(o, userId, target.name(), detail);
        if (target == OrderStatus.SCANNED) maybeReady(o);
        return true;
    }

    /** Scanned → ready to read, unless the facility waits for the MRT's confirmation. */
    private void maybeReady(ImagingOrder o) {
        if (o.getStatus() != OrderStatus.SCANNED || o.getStudyId() == null) return;
        Tenant t = tenants.findById(o.getTenantId()).orElseThrow();
        if (t.getReadyMode() == ReadyMode.WAIT_FOR_MRT && o.getMrtConfirmedAt() == null) {
            event(o, null, "WAITING_FOR_MRT", "Waiting for the MRT's \"Scan complete\"");
            return;
        }
        advance(o, OrderStatus.READY, null, t.getReadyMode() == ReadyMode.WAIT_FOR_MRT
                ? "MRT confirmed: ready to read" : "Ready to read");
        Study s = studies.findById(o.getStudyId()).orElse(null);
        if (s != null && s.getStatus() == StudyStatus.RECEIVED) ingestWorker.markReady(s);
    }

    /** The order's status including the read and the report (taken from the study). */
    OrderStatus effectiveStatus(ImagingOrder o) {
        if (o.getStatus().isClosed() || o.getStudyId() == null) return o.getStatus();
        Study s = studies.findById(o.getStudyId()).orElse(null);
        return s == null ? o.getStatus() : effectiveStatus(o, s);
    }

    public static OrderStatus effectiveStatus(ImagingOrder o, Study s) {
        if (o.getStatus().isClosed()) return o.getStatus();
        OrderStatus fromStudy = switch (s.getStatus()) {
            case READY -> OrderStatus.READY;
            case IN_PROGRESS -> OrderStatus.REPORTING;
            case REPORTED -> OrderStatus.SIGNED;
            case DELIVERED -> OrderStatus.DELIVERED;
            default -> OrderStatus.SCHEDULED;
        };
        return fromStudy.rank() > o.getStatus().rank() ? fromStudy : o.getStatus();
    }

    // ================================================================== helpers

    private ImagingOrder linkedOrder(Study study) {
        if (study.getMatchState() != MatchState.LINKED || study.getId() == null) return null;
        return orders.findByStudyId(study.getId()).orElse(null);
    }

    String nextAccession(Tenant tenant, LocalDate day) {
        int n = orders.nextCounter(tenant.getId(), day);
        if (n > 9999) throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many orders today for this hospital");
        return prefix(tenant) + "-" + day.format(ACC_DAY) + String.format("%04d", n);
    }

    private String generatePatientId(Tenant tenant) {
        int n = orders.nextCounter(tenant.getId(), PATIENT_COUNTER_DAY);
        return prefix(tenant) + "P" + String.format("%06d", n);
    }

    /** At most 5 letters/digits, so accession numbers fit DICOM's 16 characters. */
    static String prefix(Tenant tenant) {
        String raw = tenant.getAccessionPrefix() != null && !tenant.getAccessionPrefix().isBlank()
                ? tenant.getAccessionPrefix() : tenant.getCode();
        String p = raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (p.isEmpty()) p = "ORD";
        return p.length() > 5 ? p.substring(0, 5) : p;
    }

    private void event(ImagingOrder o, UUID userId, String kind, String detail) {
        events.save(OrderEvent.builder().orderId(o.getId()).at(now()).userId(userId).kind(kind)
                .detail(detail == null ? null : detail.length() > 512 ? detail.substring(0, 512) : detail).build());
    }

    private ImagingOrder requireOrder(UUID id) {
        ImagingOrder o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        tenantFor(UserPrincipal.current(), o.getTenantId());
        return o;
    }

    private Study requireHeldStudy(UUID studyId) {
        Study s = studies.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        tenantFor(UserPrincipal.current(), s.getTenantId());
        if (s.getMatchState() == null || !s.getMatchState().isHeld()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This study is not waiting for a check");
        }
        return s;
    }

    private static void requireNotClosed(ImagingOrder o) {
        if (o.getStatus().isClosed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is " + o.getStatus());
    }

    /** Hospital staff work on their own hospital; a platform admin names it. */
    static UUID tenantFor(UserPrincipal user, UUID requested) {
        if (user.getRole() == Role.HOSPITAL_USER) {
            if (requested != null && !requested.equals(user.getTenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return user.getTenantId();
        }
        if (user.getRole() == Role.ADMIN) {
            if (requested == null) throw bad("tenantId is required");
            return requested;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    OrderDto toDto(ImagingOrder o) {
        Patient p = patients.findById(o.getPatientId()).orElse(null);
        Study s = o.getStudyId() == null ? null : studies.findById(o.getStudyId()).orElse(null);
        String hospital = tenants.findById(o.getTenantId()).map(Tenant::getName).orElse(null);
        OrderStatus status = s == null ? o.getStatus() : effectiveStatus(o, s);
        boolean waiting = status == OrderStatus.SCANNED && o.getMrtConfirmedAt() == null
                && tenants.findById(o.getTenantId()).map(t -> t.getReadyMode() == ReadyMode.WAIT_FOR_MRT).orElse(false);
        return new OrderDto(o.getId(), o.getTenantId(), hospital, o.getAccessionNumber(),
                p == null ? null : new PatientDto(p.getId(), p.getPatientId(), p.getPatientName(), p.getBirthDate(), p.getSex()),
                o.getModality(), o.getProcedureId(), o.getProcedureDescription(), o.getLaterality(), o.getAdditionalInstructions(),
                o.getPriority(), o.getReferringPhysician(),
                o.getClinicalInfo(), o.getSource(), status, o.getScheduledDate(), o.getStudyId(),
                s == null ? 0 : s.getInstanceCount(),
                o.getArrivedAt(), o.getScanningAt(), o.getScannedAt(), o.getReadyAt(), o.getMrtConfirmedAt(),
                o.getCancelledAt(), o.getCancelReason(), o.getCreatedAt(), waiting,
                s != null && s.isNameDiffers(), s == null ? null : OrderMatcher.displayName(s.getScannerPatientName()),
                s != null && s.getImagesAfterReportAt() != null);
    }

    /** "CT head without contrast (Left)": the procedure with the side when there is one. */
    static String examName(ImagingOrder o) {
        String side = o.getLaterality() == null ? "" : o.getLaterality().label();
        String proc = o.getProcedureDescription();
        if (blank(proc)) return side.isEmpty() ? null : o.getModality() + " (" + side + ")";
        return side.isEmpty() ? proc : proc + " (" + side + ")";
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(facilityZone));
    }

    private Instant now() {
        return clock.instant();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String nullToDash(String s) {
        return blank(s) ? "none" : s;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
