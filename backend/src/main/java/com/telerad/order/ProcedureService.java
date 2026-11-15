package com.telerad.order;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** The central procedure list (KB note 15 §9): read by the order form, maintained by platform admins. */
@Service
@RequiredArgsConstructor
public class ProcedureService {
    /** Names shown for the modality codes; any other code shows as itself. */
    static final Map<String, String> MODALITY_LABELS = new LinkedHashMap<>();
    static {
        MODALITY_LABELS.put("DX", "X-ray");
        MODALITY_LABELS.put("CR", "X-ray (CR)");
        MODALITY_LABELS.put("US", "Ultrasound");
        MODALITY_LABELS.put("CT", "CT");
        MODALITY_LABELS.put("MR", "MRI");
        MODALITY_LABELS.put("RF", "Fluoroscopy");
        MODALITY_LABELS.put("MG", "Mammography");
        MODALITY_LABELS.put("BMD", "Bone density");
        MODALITY_LABELS.put("NM", "Nuclear medicine");
        MODALITY_LABELS.put("PT", "PET");
        MODALITY_LABELS.put("XA", "Angiography");
    }

    private final ProcedureRepository procedures;
    private final AuditService auditService;

    public record ProcedureDto(UUID id, String modality, String name, boolean active, int sortOrder) {}

    public record ModalityDto(String code, String label, int procedures) {}

    public record ProcedureUpdate(String modality, String name, Boolean active, Integer sortOrder) {}

    @Transactional(readOnly = true)
    public List<ProcedureDto> list(String modality, boolean includeInactive) {
        List<Procedure> all = includeInactive ? procedures.findAllByOrderByModalityAscSortOrderAscNameAsc()
                : procedures.findByActiveTrueOrderByModalityAscSortOrderAscNameAsc();
        String m = modality == null || modality.isBlank() ? null : modality.trim().toUpperCase(Locale.ROOT);
        return all.stream().filter(p -> m == null || p.getModality().equals(m)).map(ProcedureService::toDto).toList();
    }

    /** Modalities that have at least one active procedure, in a fixed, familiar order. */
    @Transactional(readOnly = true)
    public List<ModalityDto> modalities() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Procedure p : procedures.findByActiveTrueOrderByModalityAscSortOrderAscNameAsc()) {
            counts.merge(p.getModality(), 1, Integer::sum);
        }
        List<String> order = new ArrayList<>(MODALITY_LABELS.keySet());
        return counts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> {
                    int i = order.indexOf(e.getKey());
                    return i < 0 ? Integer.MAX_VALUE : i;
                }).thenComparing(Map.Entry::getKey))
                .map(e -> new ModalityDto(e.getKey(), label(e.getKey()), e.getValue()))
                .toList();
    }

    @Transactional
    public ProcedureDto create(ProcedureUpdate body) {
        if (body == null) throw bad("body required");
        String modality = modality(body.modality());
        String name = name(body.name());
        requireUnique(modality, name, null);
        int sort = body.sortOrder() != null ? body.sortOrder()
                : procedures.findFirstByModalityOrderBySortOrderDesc(modality).map(p -> p.getSortOrder() + 10).orElse(10);
        Procedure p = procedures.save(Procedure.builder().modality(modality).name(name)
                .active(body.active() == null || body.active()).sortOrder(sort).build());
        audit("PROCEDURE_CREATE", p);
        return toDto(p);
    }

    @Transactional
    public ProcedureDto update(UUID id, ProcedureUpdate body) {
        if (body == null) throw bad("body required");
        Procedure p = procedures.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Procedure not found"));
        String modality = body.modality() == null ? p.getModality() : modality(body.modality());
        String name = body.name() == null ? p.getName() : name(body.name());
        requireUnique(modality, name, p.getId());
        p.setModality(modality);
        p.setName(name);
        if (body.active() != null) p.setActive(body.active());
        if (body.sortOrder() != null) p.setSortOrder(body.sortOrder());
        procedures.save(p);
        audit("PROCEDURE_UPDATE", p);
        return toDto(p);
    }

    /** The procedure an order is made for: it must exist, be active and belong to the modality. */
    @Transactional(readOnly = true)
    public Procedure requireOrderable(UUID id, String modality) {
        Procedure p = procedures.findById(id).orElseThrow(() -> bad("Unknown procedure"));
        if (!p.isActive()) throw bad("This procedure is no longer offered");
        if (modality != null && !p.getModality().equalsIgnoreCase(modality)) {
            throw bad("This procedure does not belong to " + label(modality));
        }
        return p;
    }

    static String label(String code) {
        return MODALITY_LABELS.getOrDefault(code, code);
    }

    private void requireUnique(String modality, String name, UUID self) {
        procedures.findFirstByModalityAndNameIgnoreCase(modality, name)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "\"" + other.getName() + "\" already exists for " + label(modality));
                });
    }

    private static String modality(String raw) {
        String m = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!m.matches("[A-Z]{2,4}")) throw bad("Modality must be a DICOM code such as US, DX, CT, MR");
        return m;
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw bad("Name is required");
        if (n.length() > 255) throw bad("Name is too long (max 255)");
        return n;
    }

    private void audit(String action, Procedure p) {
        UserPrincipal user = UserPrincipal.current();
        auditService.record(null, user.getId(), action, "PROCEDURE", p.getId().toString(),
                p.getModality() + " · " + p.getName() + (p.isActive() ? "" : " (off)"), null);
    }

    private static ProcedureDto toDto(Procedure p) {
        return new ProcedureDto(p.getId(), p.getModality(), p.getName(), p.isActive(), p.getSortOrder());
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
