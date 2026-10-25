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

/** Time calculations shared by the emergency callback suppression provider and its tests. */
final class BlockSuppressionState {
    static final long FOREVER = -1;
    private static final int MAX_DURATION_SECONDS = 7 * 24 * 3600;

    private BlockSuppressionState() {}

    static long expiryTimeMillis(long nowMillis, int carrierSeconds, int defaultSeconds) {
        int seconds = carrierSeconds <= MAX_DURATION_SECONDS ? carrierSeconds : defaultSeconds;
        return seconds < 0 ? FOREVER : nowMillis + seconds * 1000L;
    }

    static boolean isSuppressed(long nowMillis, long expiryTimeMillis) {
        return expiryTimeMillis == FOREVER || nowMillis < expiryTimeMillis;
    }
}
