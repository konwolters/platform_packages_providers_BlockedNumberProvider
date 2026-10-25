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

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.provider.BlockedNumberContract;
import android.provider.BlockedNumberContract.SystemContract;
import android.telephony.CarrierConfigManager;

/**
 * Device-wide emergency callback suppression, separate from each user's blocked-number database.
 * Only the signature-protected internal protocol is exposed; app authorization stays in
 * {@link BlockedNumberProvider} and must run before forwarding an operation here.
 */
public class BlockSuppressionProvider extends ContentProvider {
    static final String AUTHORITY = "com.android.blockednumber.suppression";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    static final String ACCESS_PERMISSION =
            "com.android.providers.blockednumber.permission.ACCESS_BLOCK_SUPPRESSION";
    static final String METHOD_SET_NOTIFICATION = "set_emergency_call_notification";

    // Reuse the system user's existing preferences, including an active pre-upgrade suppression.
    private static final String PREF_FILE = "block_number_provider_prefs";
    private static final String EXPIRY_TIME_PREF = "block_suppression_expiry_time_pref";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call() does not enforce the manifest's read/write permissions itself.
        getContext().enforceCallingOrSelfPermission(ACCESS_PERMISSION,
                "Only the blocked-number provider may access emergency callback suppression");
        SharedPreferences preferences = getContext().getSharedPreferences(
                PREF_FILE, Context.MODE_PRIVATE);
        long expiry = preferences.getLong(EXPIRY_TIME_PREF, 0);
        boolean suppressed = BlockSuppressionState.isSuppressed(System.currentTimeMillis(), expiry);
        Bundle result = new Bundle();
        switch (method) {
            case SystemContract.METHOD_GET_BLOCK_SUPPRESSION_STATUS:
                result.putBoolean(SystemContract.RES_IS_BLOCKING_SUPPRESSED, suppressed);
                result.putLong(SystemContract.RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP, expiry);
                result.putBoolean(BlockedNumberContract.RES_SHOW_EMERGENCY_CALL_NOTIFICATION,
                        preferences.getBoolean(
                                SystemContract.ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION,
                                false));
                return result;
            case SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT:
                PersistableBundle config = getCarrierConfig();
                int defaultSeconds = CarrierConfigManager.getDefaultConfig().getInt(
                        CarrierConfigManager.KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT);
                long until = BlockSuppressionState.expiryTimeMillis(System.currentTimeMillis(),
                        config.getInt(
                                CarrierConfigManager.KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT,
                                defaultSeconds), defaultSeconds);
                SharedPreferences.Editor editor = preferences.edit()
                        .putLong(EXPIRY_TIME_PREF, until);
                if (config.getBoolean(
                        CarrierConfigManager.KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL)) {
                    editor.putBoolean(
                            SystemContract.ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION,
                            true);
                }
                editor.apply();
                notifyStateChanged();
                return result;
            case SystemContract.METHOD_END_BLOCK_SUPPRESSION:
                if (suppressed) {
                    preferences.edit().putLong(EXPIRY_TIME_PREF, 0)
                            .putBoolean(SystemContract
                                    .ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION, false)
                            .apply();
                    notifyStateChanged();
                }
                return result;
            case METHOD_SET_NOTIFICATION:
                if (extras == null) {
                    throw new IllegalArgumentException("Missing notification setting");
                }
                preferences.edit().putBoolean(
                        SystemContract.ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION,
                        extras.getBoolean(BlockedNumberContract.EXTRA_ENHANCED_SETTING_VALUE))
                        .apply();
                return result;
            default:
                throw new IllegalArgumentException("Unsupported suppression method: " + method);
        }
    }

    private PersistableBundle getCarrierConfig() {
        CarrierConfigManager manager = getContext().getSystemService(CarrierConfigManager.class);
        PersistableBundle config = manager == null ? null : manager.getConfig();
        return config == null ? CarrierConfigManager.getDefaultConfig() : config;
    }

    private void notifyStateChanged() {
        getContext().sendBroadcastAsUser(
                new Intent(SystemContract.ACTION_BLOCK_SUPPRESSION_STATE_CHANGED),
                UserHandle.ALL, Manifest.permission.READ_BLOCKED_NUMBERS);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
