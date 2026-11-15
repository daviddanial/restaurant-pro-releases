package com.telerad.order;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The central procedure list: read by reception, maintained by platform admins. */
@RestController
@RequiredArgsConstructor
public class ProcedureController {
    private final ProcedureService procedureService;

    @GetMapping("/api/procedures")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public List<ProcedureService.ProcedureDto> list(@RequestParam(required = false) String modality) {
        return procedureService.list(modality, false);
    }

    @GetMapping("/api/procedures/modalities")
    @PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN','RADIOLOGIST')")
    public List<ProcedureService.ModalityDto> modalities() {
        return procedureService.modalities();
    }

    @GetMapping("/api/admin/procedures")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProcedureService.ProcedureDto> adminList(@RequestParam(required = false) String modality) {
        return procedureService.list(modality, true);
    }

    @PostMapping("/api/admin/procedures")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureService.ProcedureDto create(@RequestBody ProcedureService.ProcedureUpdate body) {
        return procedureService.create(body);
    }

    @PutMapping("/api/admin/procedures/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProcedureService.ProcedureDto update(@PathVariable UUID id, @RequestBody ProcedureService.ProcedureUpdate body) {
        return procedureService.update(id, body);
    }
}
