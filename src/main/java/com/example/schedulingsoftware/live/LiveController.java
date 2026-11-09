package com.example.schedulingsoftware.live;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.schedulingsoftware.auth.AccessPolicy;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** The stream each open screen listens on for changes made elsewhere. */
@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveUpdates liveUpdates;
    private final AccessPolicy accessPolicy;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "client", required = false) String clientId,
            HttpServletResponse response) {
        // Tell nginx not to hold events back in its buffer.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return liveUpdates.connect(accessPolicy.current(), LiveChangeInterceptor.cleanClientId(clientId));
    }
}
