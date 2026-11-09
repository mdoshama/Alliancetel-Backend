package com.example.schedulingsoftware.live;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.schedulingsoftware.auth.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * After any change that went through (POST, PUT, PATCH or DELETE that succeeded), tells the other open screens what
 * kind of data changed. By then the service's transaction has committed, so a screen that reloads sees the change.
 */
@Component
@RequiredArgsConstructor
public class LiveChangeInterceptor implements HandlerInterceptor {

    /** Header the app sends with every call so the tab that made a change doesn't reload itself. */
    public static final String CLIENT_HEADER = "X-Client-Id";

    private final LiveUpdates liveUpdates;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (ex != null || response.getStatus() >= 400 || !changes(request.getMethod())) {
            return;
        }
        String topic = topicOf(request.getRequestURI());
        if (topic == null) {
            return;
        }
        CurrentUser actor = CurrentUser.find().orElse(null);
        liveUpdates.publish(topic, actor, cleanClientId(request.getHeader(CLIENT_HEADER)));
    }

    static boolean changes(String method) {
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /** Which screens care about a change at this path; null for anything that doesn't affect what others see. */
    static String topicOf(String path) {
        if (path.startsWith("/api/schedules")) {
            return "schedule";
        }
        if (path.startsWith("/api/day-off-requests")) {
            return "dayoff";
        }
        if (path.startsWith("/api/employees") || path.startsWith("/api/stores") || path.startsWith("/api/areas")) {
            return "people";
        }
        if (path.startsWith("/api/time-clock")) {
            return "timeclock";
        }
        return null;
    }

    /** Tab ids are random strings the app makes up; anything else is ignored. */
    static String cleanClientId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{8,64}") ? id : null;
    }
}
