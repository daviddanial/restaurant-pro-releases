package com.telerad.order;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcedureServiceTest {
    final List<Procedure> db = new ArrayList<>();
    ProcedureRepository repo = mock(ProcedureRepository.class);
    ProcedureService service = new ProcedureService(repo, mock(AuditService.class));

    @BeforeEach
    void setUp() {
        when(repo.save(any())).thenAnswer(i -> {
            Procedure p = i.getArgument(0);
            if (p.getId() == null) { p.setId(UUID.randomUUID()); db.add(p); }
            return p;
        });
        when(repo.findById(any())).thenAnswer(i -> db.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findFirstByModalityAndNameIgnoreCase(any(), any())).thenAnswer(i -> db.stream()
                .filter(p -> p.getModality().equals(i.getArgument(0)) && p.getName().equalsIgnoreCase(i.getArgument(1))).findFirst());
        when(repo.findFirstByModalityOrderBySortOrderDesc(any())).thenAnswer(i -> db.stream()
                .filter(p -> p.getModality().equals(i.getArgument(0))).max(Comparator.comparingInt(Procedure::getSortOrder)));
        when(repo.findAllByOrderByModalityAscSortOrderAscNameAsc()).thenAnswer(i -> List.copyOf(db));
        when(repo.findByActiveTrueOrderByModalityAscSortOrderAscNameAsc())
                .thenAnswer(i -> db.stream().filter(Procedure::isActive).toList());
        UserPrincipal admin = new UserPrincipal(UUID.randomUUID(), "a@x", "Admin", Role.ADMIN, null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    ProcedureService.ProcedureDto add(String modality, String name) {
        return service.create(new ProcedureService.ProcedureUpdate(modality, name, null, null));
    }

    @Test
    void createCleansAndAppendsToTheModality() {
        add("US", "Neck ultrasound");
        ProcedureService.ProcedureDto p = add(" us ", "  Thyroid   ultrasound ");
        assertThat(p.modality()).isEqualTo("US");
        assertThat(p.name()).isEqualTo("Thyroid ultrasound");
        assertThat(p.sortOrder()).isEqualTo(20);
        assertThat(p.active()).isTrue();
    }

    @Test
    void sameNameInTheSameModalityIsRejected_inAnotherModalityAllowed() {
        add("CT", "CT knee");
        assertThatThrownBy(() -> add("CT", "ct KNEE")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(add("MR", "CT knee").modality()).isEqualTo("MR");
    }

    @Test
    void badInputIsRejected() {
        assertThatThrownBy(() -> add("X-RAY", "Chest")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> add("DX", " ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void switchedOffProceduresAreHiddenFromTheFormButStayInTheAdminList() {
        ProcedureService.ProcedureDto p = add("DX", "Chest X-ray [PA]");
        add("DX", "Chest X-ray [AP]");
        service.update(p.id(), new ProcedureService.ProcedureUpdate(null, null, false, null));
        assertThat(service.list("DX", false)).extracting(ProcedureService.ProcedureDto::name).containsExactly("Chest X-ray [AP]");
        assertThat(service.list("dx", true)).hasSize(2);
        assertThatThrownBy(() -> service.requireOrderable(p.id(), "DX")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void renameKeepsUniqueness() {
        ProcedureService.ProcedureDto a = add("DX", "Knee X-ray");
        add("DX", "Knee X-ray [AP & lateral]");
        assertThatThrownBy(() -> service.update(a.id(), new ProcedureService.ProcedureUpdate(null, "knee x-ray [ap & lateral]", null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.update(a.id(), new ProcedureService.ProcedureUpdate(null, "Knee X-ray [skyline]", null, null)).name())
                .isEqualTo("Knee X-ray [skyline]");
    }

    @Test
    void orderMustUseAProcedureOfItsModality() {
        ProcedureService.ProcedureDto ct = add("CT", "CT brain without contrast");
        assertThat(service.requireOrderable(ct.id(), "CT").getName()).isEqualTo("CT brain without contrast");
        assertThatThrownBy(() -> service.requireOrderable(ct.id(), "MR")).hasMessageContaining("does not belong to MRI");
    }

    @Test
    void modalitiesInAFamiliarOrderWithLabels() {
        add("MR", "MRI knee");
        add("US", "Neck ultrasound");
        add("DX", "Chest X-ray [PA]");
        add("ZZ", "Odd one");
        assertThat(service.modalities()).extracting(ProcedureService.ModalityDto::label)
                .containsExactly("X-ray", "Ultrasound", "MRI", "ZZ");
    }
}
