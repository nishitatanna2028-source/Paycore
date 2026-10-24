package com.paycore.domain;

/** The payment state machine. Every legal transition is listed here and nowhere else. */
public enum PaymentStatus {
    CREATED, AUTHORIZED, CAPTURED, VOIDED, REFUNDED;

    public boolean canMoveTo(PaymentStatus next) {
        return switch (this) {
            case CREATED -> next == AUTHORIZED;
            case AUTHORIZED -> next == CAPTURED || next == VOIDED;
            case CAPTURED -> next == REFUNDED;
            case VOIDED, REFUNDED -> false;
        };
    }
}
