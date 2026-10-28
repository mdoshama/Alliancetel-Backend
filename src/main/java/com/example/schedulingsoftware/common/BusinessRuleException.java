package com.example.schedulingsoftware.common;

/** Thrown when a request is valid in shape but breaks a business rule (returned as HTTP 409). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
