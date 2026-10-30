package com.example.schedulingsoftware.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the WhatsApp sender in the background. Tests switch it off and call the sender directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "alliancetel.background-jobs", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
