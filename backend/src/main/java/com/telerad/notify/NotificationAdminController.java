package com.telerad.notify;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class NotificationAdminController {
    private final NotificationEventRepository eventRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<NotificationEvent> recent() {
        return eventRepository.findTop50ByOrderByCreatedAtDesc();
    }
}
