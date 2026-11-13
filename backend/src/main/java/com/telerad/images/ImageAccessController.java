package com.telerad.images;

import com.telerad.auth.UserPrincipal;
import com.telerad.gateway.Gateway;
import com.telerad.gateway.GatewayRepository;
import com.telerad.study.Study;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/studies/{studyId}")
@RequiredArgsConstructor
public class ImageAccessController {
    private final ImageLocationService imageLocationService;
    private final EdgeTokenService edgeTokenService;
    private final GatewayRepository gatewayRepository;

    public record EdgePass(UUID gatewayId, String lanUrl, String studyInstanceUid, String token, Instant expiresAt) {}

    /** Where the study's images are: hospital server, cloud, or both. */
    @GetMapping("/image-access")
    public ImageLocationService.ImageAccess imageAccess(@PathVariable UUID studyId) {
        return imageLocationService.describe(studyId);
    }

    /** Ask the hospital server to upload the study (and priors) for a remote read. */
    @PostMapping("/cloud-copy")
    public ImageLocationService.ImageAccess requestCloudCopy(@PathVariable UUID studyId) {
        return imageLocationService.requestCloudCopy(studyId);
    }

    /** Pass for loading this study's images straight from the hospital server. */
    @PostMapping("/edge-pass")
    @Transactional(readOnly = true)
    public EdgePass edgePass(@PathVariable UUID studyId) {
        Study study = imageLocationService.requireViewable(studyId);
        if (!ImageLocationService.onHospitalServer(study)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Study is not on a hospital server");
        }
        Gateway gateway = gatewayRepository.findById(study.getEdgeGatewayId())
                .filter(Gateway::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Hospital server disabled"));
        if (gateway.getLanUrl() == null || gateway.getLanUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hospital server has no image service address");
        }
        UserPrincipal user = UserPrincipal.current();
        EdgeTokenService.Pass pass = edgeTokenService.issue(gateway.getId(), user.getId(), study.getTenantId(),
                List.of(study.getStudyInstanceUid()));
        return new EdgePass(gateway.getId(), gateway.getLanUrl(), study.getStudyInstanceUid(),
                pass.token(), pass.expiresAt());
    }
}
