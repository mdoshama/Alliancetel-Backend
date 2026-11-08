package com.example.schedulingsoftware.dayoff;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The one row holding whether the day-off request portal is on its normal Friday cycle, opened or closed. */
@Entity
@Table(name = "request_portal")
@Getter
@Setter
@NoArgsConstructor
public class RequestPortalSetting {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PortalMode mode = PortalMode.AUTO;

    @Column(length = 120)
    private String changedBy;

    private Instant changedAt;

    /** When reserved days the system once approved by itself were put back to pending (done once). */
    private Instant reservedApprovalsUndoneAt;
}
