/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.providers.blockednumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Runs against the production time calculation without Android framework dependencies. */
public class BlockSuppressionStateTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final int DEFAULT_SECONDS = 7200;
    private static final int ONE_WEEK_SECONDS = 7 * 24 * 3600;

    @Test
    public void carrierDurationIsApplied() {
        assertEquals(NOW + 1000_000,
                BlockSuppressionState.expiryTimeMillis(NOW, 1000, DEFAULT_SECONDS));
    }

    @Test
    public void negativeDurationMeansIndefiniteSuppression() {
        assertEquals(BlockSuppressionState.FOREVER,
                BlockSuppressionState.expiryTimeMillis(NOW, -1, DEFAULT_SECONDS));
        assertEquals(BlockSuppressionState.FOREVER,
                BlockSuppressionState.expiryTimeMillis(NOW, Integer.MIN_VALUE, DEFAULT_SECONDS));
    }

    @Test
    public void maximumCarrierDurationIsAccepted() {
        assertEquals(NOW + ONE_WEEK_SECONDS * 1000L,
                BlockSuppressionState.expiryTimeMillis(NOW, ONE_WEEK_SECONDS, DEFAULT_SECONDS));
    }

    @Test
    public void excessiveCarrierDurationUsesDefault() {
        assertEquals(NOW + DEFAULT_SECONDS * 1000L,
                BlockSuppressionState.expiryTimeMillis(NOW,
                        ONE_WEEK_SECONDS + 1, DEFAULT_SECONDS));
        assertEquals(NOW + DEFAULT_SECONDS * 1000L,
                BlockSuppressionState.expiryTimeMillis(NOW, Integer.MAX_VALUE, DEFAULT_SECONDS));
    }

    @Test
    public void indefiniteDefaultIsPreserved() {
        assertEquals(BlockSuppressionState.FOREVER,
                BlockSuppressionState.expiryTimeMillis(NOW, Integer.MAX_VALUE, -1));
    }

    @Test
    public void zeroDurationExpiresImmediately() {
        long expiry = BlockSuppressionState.expiryTimeMillis(NOW, 0, DEFAULT_SECONDS);
        assertEquals(NOW, expiry);
        assertFalse(BlockSuppressionState.isSuppressed(NOW, expiry));
    }

    @Test
    public void defaultStateIsNotSuppressed() {
        assertFalse(BlockSuppressionState.isSuppressed(NOW, 0));
    }

    @Test
    public void indefiniteStateDoesNotExpire() {
        assertTrue(BlockSuppressionState.isSuppressed(NOW, BlockSuppressionState.FOREVER));
        assertTrue(BlockSuppressionState.isSuppressed(
                Long.MAX_VALUE, BlockSuppressionState.FOREVER));
    }

    @Test
    public void expiryBoundaryIsExclusive() {
        long expiry = NOW + 1000;
        assertTrue(BlockSuppressionState.isSuppressed(expiry - 1, expiry));
        assertFalse(BlockSuppressionState.isSuppressed(expiry, expiry));
        assertFalse(BlockSuppressionState.isSuppressed(expiry + 1, expiry));
    }

    @Test
    public void finiteDurationsRemainActiveUntilTheirOwnExpiry() {
        for (int seconds = 1; seconds <= ONE_WEEK_SECONDS; seconds += 7919) {
            long expiry = BlockSuppressionState.expiryTimeMillis(NOW, seconds, DEFAULT_SECONDS);
            assertEquals(NOW + seconds * 1000L, expiry);
            assertTrue(BlockSuppressionState.isSuppressed(NOW, expiry));
            assertTrue(BlockSuppressionState.isSuppressed(expiry - 1, expiry));
            assertFalse(BlockSuppressionState.isSuppressed(expiry, expiry));
        }
    }
}
