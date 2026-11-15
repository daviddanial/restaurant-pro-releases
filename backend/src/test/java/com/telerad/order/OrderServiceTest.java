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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The order rules of KB note 15, with in-memory repositories. */
class OrderServiceTest {
    final Map<UUID, ImagingOrder> orderDb = new HashMap<>();
    final Map<UUID, Patient> patientDb = new HashMap<>();
    final Map<UUID, Study> studyDb = new HashMap<>();
    final List<OrderEvent> eventDb = new ArrayList<>();
    final AtomicInteger counter = new AtomicInteger();

    ImagingOrderRepository orders = mock(ImagingOrderRepository.class);
    OrderEventRepository events = mock(OrderEventRepository.class);
    PatientRepository patients = mock(PatientRepository.class);
    StudyRepository studies = mock(StudyRepository.class);
    TenantRepository tenants = mock(TenantRepository.class);
    AuditService audit = mock(AuditService.class);
    IngestWorker ingestWorker = mock(IngestWorker.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    ProcedureService procedureService = mock(ProcedureService.class);

    OrderService service;
    Tenant tenant;
    Instant now = Instant.parse("2026-11-13T07:00:00Z");

    @BeforeEach
    void setUp() {
        tenant = Tenant.builder().id(UUID.randomUUID()).code("CITYMED").name("City Medical Center")
                .ordersEnabled(true).readyMode(ReadyMode.AUTO).scanStableSeconds(120).build();
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));

        when(orders.save(any())).thenAnswer(i -> {
            ImagingOrder o = i.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            if (o.getCreatedAt() == null) o.setCreatedAt(now);
            orderDb.put(o.getId(), o);
            return o;
        });
        when(orders.findById(any())).thenAnswer(i -> Optional.ofNullable(orderDb.get((UUID) i.getArgument(0))));
        when(orders.findByStudyId(any())).thenAnswer(i -> orderDb.values().stream()
                .filter(o -> i.getArgument(0).equals(o.getStudyId())).findFirst());
        when(orders.findByTenantIdAndAccessionNumber(any(), any())).thenAnswer(i -> orderDb.values().stream()
                .filter(o -> o.getAccessionNumber().equals(i.getArgument(1))).findFirst());
        when(orders.findOpenCandidates(any(), any(), any(), any(), any())).thenAnswer(i -> {
            Collection<UUID> pids = i.getArgument(1);
            Collection<OrderStatus> open = i.getArgument(4);
            return orderDb.values().stream().filter(o -> pids.contains(o.getPatientId())
                    && o.getModality().equals(i.getArgument(2)) && o.getScheduledDate().equals(i.getArgument(3))
                    && o.getStudyId() == null && open.contains(o.getStatus())).toList();
        });
        when(orders.findByStatusAndStudyIdIsNotNull(any())).thenAnswer(i -> orderDb.values().stream()
                .filter(o -> o.getStatus() == i.getArgument(0) && o.getStudyId() != null).toList());
        when(orders.nextCounter(any(), any())).thenAnswer(i -> counter.incrementAndGet());

        when(patients.save(any())).thenAnswer(i -> {
            Patient p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            patientDb.put(p.getId(), p);
            return p;
        });
        when(patients.findById(any())).thenAnswer(i -> Optional.ofNullable(patientDb.get((UUID) i.getArgument(0))));
        when(patients.findByTenantIdAndPatientId(any(), any())).thenAnswer(i -> patientDb.values().stream()
                .filter(p -> p.getPatientId().equals(i.getArgument(1))).findFirst());

        when(studies.save(any())).thenAnswer(i -> {
            Study s = i.getArgument(0);
            studyDb.put(s.getId(), s);
            return s;
        });
        when(studies.findById(any())).thenAnswer(i -> Optional.ofNullable(studyDb.get((UUID) i.getArgument(0))));

        when(events.save(any())).thenAnswer(i -> {
            eventDb.add(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            ((java.util.function.Consumer<Object>) i.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        doAnswer(i -> {
            Study s = i.getArgument(0);
            s.setStatus(StudyStatus.READY);
            return null;
        }).when(ingestWorker).markReady(any());

        service = new OrderService(orders, events, patients, studies, tenants, audit, procedureService, ingestWorker, tx,
                "Africa/Addis_Ababa");
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
        login(Role.HOSPITAL_USER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    void login(Role role) {
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "staff@x", "Staff", role, tenant.getId(), null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    OrderService.OrderDto order(String mrn, String name) {
        return service.create(new OrderService.CreateOrder(null, mrn, name, LocalDate.of(1980, 5, 1), "F", "ct",
                "CT head", StudyPriority.URGENT, null, null, null, null, null, null));
    }

    OrderService.OrderDto sidedOrder(Laterality side, String instructions) {
        return service.create(new OrderService.CreateOrder(null, "MRN-K", "Kebede Abebe", null, "M", "DX",
                "X-ray knee AP/lateral", StudyPriority.ROUTINE, null, null, null, side, instructions, null));
    }

    /** A new study with its first image, as IngestWorker hands it over. */
    Study image(String accession, String mrn, String name) {
        Study s = Study.builder().id(UUID.randomUUID()).tenantId(tenant.getId()).studyInstanceUid("1.2." + studyDb.size())
                .accessionNumber(accession).modality("CT").instanceCount(1).createdAt(now).build();
        studyDb.put(s.getId(), s);
        boolean ready = service.onImageReceived(s, new OrderService.ScannerData(accession, mrn, name, null, "CT",
                LocalDate.of(2026, 11, 13)), true);
        if (ready && s.getStatus() == StudyStatus.RECEIVED) s.setStatus(StudyStatus.READY);
        return s;
    }

    ImagingOrder stored(OrderService.OrderDto dto) {
        return orderDb.get(dto.id());
    }

    void later(int seconds) {
        now = now.plusSeconds(seconds);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ order creation

    @Test
    void accessionFitsDicomAndCountsPerDay() {
        OrderService.OrderDto a = order("MRN-1", "Kebede Abebe");
        OrderService.OrderDto b = order("MRN-2", "Almaz Tesfaye");
        assertThat(a.accessionNumber()).isEqualTo("CITYM-2611130001");
        assertThat(b.accessionNumber()).isEqualTo("CITYM-2611130002");
        assertThat(a.accessionNumber().length()).isLessThanOrEqualTo(16);
        assertThat(a.status()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(a.modality()).isEqualTo("CT");
        assertThat(patientDb.values()).allMatch(Patient::isRegistered);

        tenant.setAccessionPrefix("CMC");
        assertThat(order("MRN-3", "X Y").accessionNumber()).isEqualTo("CMC-2611130003");
    }

    @Test
    void blankPatientIdIsGenerated() {
        OrderService.OrderDto a = order(null, "Kebede Abebe");
        assertThat(a.patient().mrn()).isEqualTo("CITYMP000001");
    }

    @Test
    void reorderForKnownPatientUpdatesTheRegisteredDemographics() {
        order("MRN-1", "Kebede Abebe");
        order("MRN-1", "Kebede Abebe Tola");
        assertThat(patientDb).hasSize(1);
        assertThat(patientDb.values().iterator().next().getPatientName()).isEqualTo("Kebede Abebe Tola");
    }

    // ------------------------------------------------------------------ matching

    @Test
    void accessionAndPatientIdMatch_linksAndUsesTheOrdersData() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);

        assertThat(s.getMatchState()).isEqualTo(MatchState.LINKED);
        assertThat(s.getPatientId()).isEqualTo(o.patient().id());
        assertThat(s.getPriority()).isEqualTo(StudyPriority.URGENT);
        assertThat(s.getStudyDescription()).isEqualTo("CT head");
        assertThat(s.isNameDiffers()).isFalse(); // the MRT did not type a name
        assertThat(s.getStatus()).isEqualTo(StudyStatus.RECEIVED); // still scanning
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.SCANNING);
        assertThat(stored(o).getStudyId()).isEqualTo(s.getId());
    }

    @Test
    void patientIdComparisonIgnoresCaseSpacesAndLeadingZeros() {
        OrderService.OrderDto o = order("mrn-0042", "Kebede Abebe");
        Study s = image(o.accessionNumber(), " MRN-0042 ", null);
        assertThat(s.getMatchState()).isEqualTo(MatchState.LINKED);
    }

    @Test
    void sameWordsInAnotherOrderAreTheSameName() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", "ABEBE^KEBEDE");
        assertThat(s.isNameDiffers()).isFalse();
    }

    @Test
    void differentNameStillLinksButIsFlagged() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", "KEBEDE^ABEBA");
        assertThat(s.getMatchState()).isEqualTo(MatchState.LINKED);
        assertThat(s.isNameDiffers()).isTrue();
        assertThat(s.getMatchNote()).contains("ABEBA KEBEDE").contains("order's name is used");
        assertThat(s.getPatientId()).isEqualTo(o.patient().id());
    }

    @Test
    void differentPatientIdIsNeverLinkedAutomatically() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-9", "ABEBE^KEBEDE");
        assertThat(s.getMatchState()).isEqualTo(MatchState.NEEDS_CHECK);
        assertThat(s.getSuggestedOrderId()).isEqualTo(o.id());
        assertThat(s.getMatchNote()).contains("MRN-9").contains("MRN-1");
        assertThat(s.getStatus()).isEqualTo(StudyStatus.RECEIVED);
        assertThat(stored(o).getStudyId()).isNull();
        assertThat(service.readyAllowed(s)).isFalse();
    }

    @Test
    void secondStudyForAnOrderNeedsACheck() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        image(o.accessionNumber(), "MRN-1", null);
        Study second = image(o.accessionNumber(), "MRN-1", null);
        assertThat(second.getMatchState()).isEqualTo(MatchState.NEEDS_CHECK);
        assertThat(second.getMatchNote()).contains("already has images");
    }

    @Test
    void imagesForACancelledOrderNeedACheck() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        service.cancel(o.id(), "Wrong exam");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        assertThat(s.getMatchState()).isEqualTo(MatchState.NEEDS_CHECK);
    }

    @Test
    void noAccessionButOneOrderFits_isOnlySuggested() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(null, "MRN-1", null);
        assertThat(s.getMatchState()).isEqualTo(MatchState.SUGGESTED);
        assertThat(s.getSuggestedOrderId()).isEqualTo(o.id());
        assertThat(service.readyAllowed(s)).isFalse();
    }

    @Test
    void noAccessionAndTwoOrdersFit_isUnmatched() {
        order("MRN-1", "Kebede Abebe");
        order("MRN-1", "Kebede Abebe");
        Study s = image(null, "MRN-1", null);
        assertThat(s.getMatchState()).isEqualTo(MatchState.UNMATCHED);
    }

    @Test
    void unknownAccessionAndUnknownPatient_isUnmatched() {
        Study s = image("NOPE-1", "MRN-77", "X^Y");
        assertThat(s.getMatchState()).isEqualTo(MatchState.UNMATCHED);
        assertThat(s.getMatchNote()).contains("NOPE-1");
        assertThat(s.getScannerPatientId()).isEqualTo("MRN-77");
    }

    @Test
    void facilityWithoutOrders_keepsTodaysBehaviour() {
        tenant.setOrdersEnabled(false);
        Study s = image("ANY", "MRN-1", "X^Y");
        assertThat(s.getMatchState()).isNull();
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
    }

    @Test
    void studiesFromBeforeOrdersWereSwitchedOnAreLeftAlone() {
        Study old = Study.builder().id(UUID.randomUUID()).tenantId(tenant.getId()).studyInstanceUid("1.9")
                .status(StudyStatus.READY).createdAt(now).build();
        boolean ready = service.onImageReceived(old, new OrderService.ScannerData(null, "MRN-1", null, null, "CT", null), false);
        assertThat(ready).isTrue();
        assertThat(old.getMatchState()).isNull();
    }

    // ------------------------------------------------------------------ statuses

    @Test
    void auto_scanFinishesWhenImagesStop_thenReadyToRead() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);

        later(60);
        service.checkStableScans();
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.SCANNING); // not yet stable

        later(90);
        service.checkStableScans();
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
        verify(ingestWorker).markReady(s);
    }

    @Test
    void auto_forgottenScanCompleteDoesNotBlock_andALateClickChangesNothing() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        later(200);
        service.checkStableScans();
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);

        OrderService.OrderDto after = service.scanComplete(o.id());
        assertThat(after.status()).isEqualTo(OrderStatus.READY);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
        assertThat(eventDb).anyMatch(e -> e.getKind().equals("LATE_EVENT") && e.getDetail().contains("status unchanged"));
        assertThat(stored(o).getMrtConfirmedAt()).isNotNull();
    }

    @Test
    void waitForMrt_scannedStudyWaitsForTheClick() {
        tenant.setReadyMode(ReadyMode.WAIT_FOR_MRT);
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        later(200);
        service.checkStableScans();
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.SCANNED);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.RECEIVED);
        assertThat(service.toDto(stored(o)).waitingForMrt()).isTrue();

        service.scanComplete(o.id());
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
    }

    @Test
    void waitForMrt_clickWhileImagesArriveMakesItReadyAtOnce() {
        tenant.setReadyMode(ReadyMode.WAIT_FOR_MRT);
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        service.scanComplete(o.id());
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
    }

    @Test
    void waitForMrt_clickBeforeAnyImageCountsWhenTheImagesArrive() {
        tenant.setReadyMode(ReadyMode.WAIT_FOR_MRT);
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        service.scanComplete(o.id());
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.SCHEDULED);

        Study s = image(o.accessionNumber(), "MRN-1", null);
        later(200);
        service.checkStableScans();
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
    }

    @Test
    void statusNeverGoesBackwards() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        image(o.accessionNumber(), "MRN-1", null);
        assertThat(service.arrived(o.id()).status()).isEqualTo(OrderStatus.SCANNING);
        assertThat(service.startScan(o.id()).status()).isEqualTo(OrderStatus.SCANNING);
        assertThat(eventDb.stream().filter(e -> e.getKind().equals("LATE_EVENT")).count()).isEqualTo(2);
    }

    @Test
    void hospitalServerCompleteMarksScanned() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        when(studies.findByTenantIdAndStudyInstanceUid(tenant.getId(), s.getStudyInstanceUid())).thenReturn(Optional.of(s));
        service.onStudyComplete(tenant.getId(), s.getStudyInstanceUid());
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY);
    }

    @Test
    void readReportAndDeliveryComeFromTheStudy() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        later(200);
        service.checkStableScans();
        s.setStatus(StudyStatus.IN_PROGRESS);
        assertThat(service.toDto(stored(o)).status()).isEqualTo(OrderStatus.REPORTING);
        s.setStatus(StudyStatus.REPORTED);
        assertThat(service.toDto(stored(o)).status()).isEqualTo(OrderStatus.SIGNED);
        s.setStatus(StudyStatus.DELIVERED);
        assertThat(service.toDto(stored(o)).status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void cannotCancelOnceScanningStarted() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        image(o.accessionNumber(), "MRN-1", null);
        assertThatThrownBy(() -> service.cancel(o.id(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void imagesAfterTheReportAreRecorded() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        s.setStatus(StudyStatus.REPORTED);
        service.onImageReceived(s, new OrderService.ScannerData(o.accessionNumber(), "MRN-1", null, null, "CT", null), false);
        assertThat(eventDb).anyMatch(e -> e.getKind().equals("IMAGES_AFTER_REPORT"));
        assertThat(s.getStatus()).isEqualTo(StudyStatus.REPORTED);
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    void personLinksAHeldStudy_andItGoesOnWhenStable() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-9", null);
        later(200);
        service.linkByPerson(s.getId(), o.id());
        assertThat(s.getMatchState()).isEqualTo(MatchState.LINKED);
        assertThat(s.getPatientId()).isEqualTo(o.patient().id());
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.READY); // images stopped long ago
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
        assertThat(eventDb).anyMatch(e -> e.getKind().equals("LINKED") && e.getDetail().contains("MRN-9"));
        verify(audit).record(eq(tenant.getId()), any(), eq("ORDER_LINK"), any(), any(), any(), any());
    }

    @Test
    void rejectingASuggestionKeepsTheStudyHeld() {
        order("MRN-1", "Kebede Abebe");
        Study s = image(null, "MRN-1", null);
        service.rejectSuggestion(s.getId());
        assertThat(s.getMatchState()).isEqualTo(MatchState.UNMATCHED);
        assertThat(s.getSuggestedOrderId()).isNull();
        assertThat(s.getStatus()).isEqualTo(StudyStatus.RECEIVED);
    }

    @Test
    void releaseWithoutOrderNeedsAReasonAndMakesItReadable() {
        Study s = image(null, "MRN-77", null);
        assertThatThrownBy(() -> service.releaseWithoutOrder(s.getId(), " ")).isInstanceOf(ResponseStatusException.class);
        service.releaseWithoutOrder(s.getId(), "Emergency, order to follow");
        assertThat(s.getMatchState()).isEqualTo(MatchState.RELEASED);
        assertThat(s.getStatus()).isEqualTo(StudyStatus.READY);
    }

    @Test
    void cannotLinkAStudyThatIsNotHeld() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        Study s = image(o.accessionNumber(), "MRN-1", null);
        OrderService.OrderDto other = order("MRN-1", "Kebede Abebe");
        assertThatThrownBy(() -> service.linkByPerson(s.getId(), other.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void otherHospitalsStaffCannotSeeOrders() {
        OrderService.OrderDto o = order("MRN-1", "Kebede Abebe");
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "x@y", "X", Role.HOSPITAL_USER, UUID.randomUUID(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
        assertThatThrownBy(() -> service.detail(o.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ------------------------------------------------------------------ laterality, instructions

    @Test
    void lateralityAndInstructionsAreKeptAndTheSideJoinsTheExamName() {
        OrderService.OrderDto o = sidedOrder(Laterality.LEFT, "  Patient on wheelchair  ");
        assertThat(o.laterality()).isEqualTo(Laterality.LEFT);
        assertThat(o.additionalInstructions()).isEqualTo("Patient on wheelchair");
        Study s = image(o.accessionNumber(), "MRN-K", null);
        s.setModality("DX");
        assertThat(s.getStudyDescription()).isEqualTo("X-ray knee AP/lateral (Left)");
    }

    @Test
    void lateralityDefaultsToNotApplicable() {
        OrderService.OrderDto o = sidedOrder(null, " ");
        assertThat(o.laterality()).isEqualTo(Laterality.NOT_APPLICABLE);
        assertThat(o.additionalInstructions()).isNull();
        Study s = image(o.accessionNumber(), "MRN-K", null);
        assertThat(s.getStudyDescription()).isEqualTo("X-ray knee AP/lateral");
    }

    @Test
    void sideWithoutProcedureUsesTheModality() {
        ImagingOrder o = ImagingOrder.builder().modality("US").laterality(Laterality.BILATERAL).build();
        assertThat(OrderService.examName(o)).isEqualTo("US (Bilateral)");
        o.setLaterality(Laterality.NOT_APPLICABLE);
        assertThat(OrderService.examName(o)).isNull();
    }

    // ------------------------------------------------------------------ procedure list

    @Test
    void procedureFromTheListGivesTheNameAndIsChecked() {
        UUID pid = UUID.randomUUID();
        when(procedureService.requireOrderable(pid, "DX")).thenReturn(
                Procedure.builder().id(pid).modality("DX").name("Knee X-ray [AP & lateral]").build());
        OrderService.OrderDto o = service.create(new OrderService.CreateOrder(null, "MRN-P", "Kebede Abebe", null, null,
                "dx", "typed text is ignored", StudyPriority.ROUTINE, null, null, null, Laterality.RIGHT, null, pid));
        assertThat(o.procedureId()).isEqualTo(pid);
        assertThat(o.procedureDescription()).isEqualTo("Knee X-ray [AP & lateral]");
        Study s = image(o.accessionNumber(), "MRN-P", null);
        assertThat(s.getStudyDescription()).isEqualTo("Knee X-ray [AP & lateral] (Right)");

        UUID ct = UUID.randomUUID();
        when(procedureService.requireOrderable(ct, "DX")).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "The procedure is not a X-ray procedure"));
        assertThatThrownBy(() -> service.create(new OrderService.CreateOrder(null, "MRN-P", "Kebede Abebe", null, null,
                "DX", null, StudyPriority.ROUTINE, null, null, null, null, null, ct)))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------ pure rules

    @Test
    void matcherRules() {
        assertThat(OrderMatcher.samePatientId("00123", "123")).isTrue();
        assertThat(OrderMatcher.samePatientId("ab 12", "AB12")).isTrue();
        assertThat(OrderMatcher.samePatientId("123", "124")).isFalse();
        assertThat(OrderMatcher.samePatientId(null, null)).isFalse();
        assertThat(OrderMatcher.nameMatches("Kebede Abebe", "ABEBE^KEBEDE")).isTrue();
        assertThat(OrderMatcher.nameMatches("Kebede Abebe", "")).isTrue();
        assertThat(OrderMatcher.nameMatches("Kebede Abebe", "KEBEDE^ABEBA")).isFalse();
        assertThat(OrderMatcher.nameMatches("José Núñez", "NUNEZ^JOSE")).isTrue();
        assertThat(OrderMatcher.displayName("ABEBE^KEBEDE^T")).isEqualTo("KEBEDE T ABEBE");
        assertThat(OrderStatus.SIGNED.isBefore(OrderStatus.READY)).isFalse();
        assertThat(OrderStatus.CANCELLED.isBefore(OrderStatus.READY)).isFalse();
    }
}
