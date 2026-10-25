package com.paycore.common;

import java.util.UUID;

public final class SystemAccounts {
    public static final UUID FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** The original single escrow account. Still used when sharding is off and for pre-V4 payments. */
    public static final UUID ESCROW = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Number of escrow shard accounts created by migration V4. */
    public static final int MAX_ESCROW_SHARDS = 16;

    private SystemAccounts() {}

    /**
     * Picks the escrow account for a payment. With 1 shard (default) everything uses ESCROW.
     * Otherwise the payment id's hash picks one of the accounts 00000000-0000-0000-0002-0000000000NN.
     */
    public static UUID escrowFor(UUID paymentId, int shards) {
        if (shards <= 1) return ESCROW;
        int index = Math.floorMod(paymentId.hashCode(), Math.min(shards, MAX_ESCROW_SHARDS));
        return new UUID(0L, 0x0002000000000000L | index);
    }
}
