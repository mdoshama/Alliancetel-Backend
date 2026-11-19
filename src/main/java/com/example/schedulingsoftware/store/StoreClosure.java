package com.example.schedulingsoftware.store;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A date a store is closed for (a holiday such as Thanksgiving), on top of its weekly hours. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreClosure {

    @Column(name = "closed_on", nullable = false)
    private LocalDate date;

    /** What it's for, e.g. "Thanksgiving". Optional. */
    @Column(length = 60)
    private String label;

    public StoreClosure(LocalDate date, String label) {
        this.date = date;
        this.label = label;
    }
}
