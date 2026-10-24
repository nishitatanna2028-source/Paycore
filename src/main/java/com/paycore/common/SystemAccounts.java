package com.paycore.common;

import java.util.UUID;

public final class SystemAccounts {
    public static final UUID FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ESCROW = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private SystemAccounts() {}
}
