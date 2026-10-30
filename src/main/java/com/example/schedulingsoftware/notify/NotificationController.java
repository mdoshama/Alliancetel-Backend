package com.example.schedulingsoftware.notify;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.notify.NotificationDtos.AnnounceRequest;
import com.example.schedulingsoftware.notify.NotificationDtos.AnnounceResult;
import com.example.schedulingsoftware.notify.NotificationDtos.NotificationResponse;
import com.example.schedulingsoftware.notify.NotificationDtos.WhatsAppStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Messages (email and WhatsApp): history, connection status, announcements. Admins and managers only. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public PageResponse<NotificationResponse> page(@RequestParam(required = false) Long areaId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.page(areaId, status, channel, page, size);
    }

    @GetMapping("/status")
    public WhatsAppStatus status() {
        return service.status();
    }

    @PostMapping("/announce")
    public AnnounceResult announce(@Valid @RequestBody AnnounceRequest body) {
        return service.announce(body.areaId(), body.message());
    }

    @PostMapping("/{id}/retry")
    public NotificationResponse retry(@PathVariable Long id) {
        return service.retry(id);
    }
}
