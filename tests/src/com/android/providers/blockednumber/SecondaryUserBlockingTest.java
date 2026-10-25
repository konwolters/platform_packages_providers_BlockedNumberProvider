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

import static android.os.UserHandle.MIN_SECONDARY_USER_ID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Country;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.BlockedNumberContract;
import android.provider.BlockedNumberContract.SystemContract;
import android.provider.BlockedNumberContract.BlockedNumbers;
import android.test.AndroidTestCase;

import androidx.test.filters.MediumTest;

/** Tests for user-local call blocking and device-wide emergency callback suppression. */
@MediumTest
public class SecondaryUserBlockingTest extends AndroidTestCase {
    private static final String NUMBER = "+12025550123";

    private MyMockContext mSecondaryContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        BlockedNumberProvider.ALLOW_SELF_CALL = false;
        mSecondaryContext = newSecondaryContext(MIN_SECONDARY_USER_ID);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            mSecondaryContext.shutdown();
        } finally {
            super.tearDown();
        }
    }

    public void testFullSecondaryUserCanBlockNumbers() {
        assertTrue(BlockedNumberContract.canCurrentUserBlockNumbers(mSecondaryContext));
    }

    public void testSecondaryCapabilityDescribesCallsOnly() {
        Bundle result = mSecondaryContext.getContentResolver().call(
                BlockedNumberContract.AUTHORITY_URI,
                BlockedNumberContract.METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS, null, null);
        assertTrue(result.getBoolean(BlockedNumberProvider.RES_CALL_BLOCKING_ONLY));
    }

    public void testUnsupportedUserTypesRemainIneligible() {
        when(mSecondaryContext.mUserManager.isUserOfType(UserManager.USER_TYPE_FULL_SECONDARY))
                .thenReturn(false);
        assertFalse(BlockedNumberContract.canCurrentUserBlockNumbers(mSecondaryContext));
    }

    public void testGuestRestrictedDemoAndPrivateUsersAreNotEnabled() {
        for (String userType : new String[] {
                UserManager.USER_TYPE_FULL_GUEST, UserManager.USER_TYPE_FULL_RESTRICTED,
                UserManager.USER_TYPE_FULL_DEMO, UserManager.USER_TYPE_PROFILE_PRIVATE}) {
            when(mSecondaryContext.mUserManager.isUserOfType(anyString()))
                    .thenAnswer(invocation -> userType.equals(invocation.getArgument(0)));
            assertFalse(userType,
                    BlockedNumberContract.canCurrentUserBlockNumbers(mSecondaryContext));
        }
    }

    public void testHighSecondaryUserIdDoesNotChangeEligibility() {
        doReturn(1000).when(mSecondaryContext).getUserId();
        assertTrue(BlockedNumberContract.canCurrentUserBlockNumbers(mSecondaryContext));
    }

    public void testMainUserDoesNotBecomeCallBlockingOnly() {
        when(mSecondaryContext.mUserManager.isMainUser()).thenReturn(true);
        Bundle result = mSecondaryContext.getContentResolver().call(
                BlockedNumberContract.AUTHORITY_URI,
                BlockedNumberContract.METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS, null, null);
        assertFalse(result.getBoolean(BlockedNumberProvider.RES_CALL_BLOCKING_ONLY));
    }

    public void testPrivilegedSecondaryCallerCanManageLocalList() {
        block(mSecondaryContext, NUMBER);

        assertTrue(BlockedNumberContract.isBlocked(mSecondaryContext, NUMBER));
        assertRowCount(mSecondaryContext, 1);
        assertEquals(1, BlockedNumberContract.unblock(mSecondaryContext, NUMBER));
        assertFalse(BlockedNumberContract.isBlocked(mSecondaryContext, NUMBER));
        assertRowCount(mSecondaryContext, 0);
    }

    public void testCapabilityDoesNotGrantUnprivilegedCallerAccess() {
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mSecondaryContext).checkCallingPermission(anyString());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mSecondaryContext).checkCallingOrSelfPermission(anyString());

        // The capability describes the user, not the caller's app permissions.
        assertTrue(BlockedNumberContract.canCurrentUserBlockNumbers(mSecondaryContext));

        expectSecurityException(() -> block(mSecondaryContext, NUMBER));
        expectSecurityException(() -> {
            try (Cursor ignored = mSecondaryContext.getContentResolver().query(
                    BlockedNumbers.CONTENT_URI, null, null, null, null)) {
                fail("An unprivileged caller must not read the list");
            }
        });
        expectSecurityException(() -> mSecondaryContext.getContentResolver().delete(
                BlockedNumbers.CONTENT_URI, null, null));
        expectSecurityException(() -> BlockedNumberContract.isBlocked(mSecondaryContext, NUMBER));
        expectSecurityException(() -> BlockedNumberContract.unblock(mSecondaryContext, NUMBER));
    }

    public void testSeparateProviderInstancesDoNotShareBlockedRows() {
        MyMockContext otherContext = newSecondaryContext(MIN_SECONDARY_USER_ID + 1);
        try {
            block(mSecondaryContext, NUMBER);
            assertTrue(BlockedNumberContract.isBlocked(mSecondaryContext, NUMBER));
            assertFalse(BlockedNumberContract.isBlocked(otherContext, NUMBER));
            assertRowCount(otherContext, 0);

            // The same number may be independently blocked in both users.
            block(otherContext, NUMBER);
            assertEquals(1, BlockedNumberContract.unblock(mSecondaryContext, NUMBER));
            assertTrue(BlockedNumberContract.isBlocked(otherContext, NUMBER));
        } finally {
            otherContext.shutdown();
        }
    }

    public void testEmergencySuppressionIsSharedWithoutSharingBlockedRows() {
        MyMockContext otherContext = newSecondaryContext(MIN_SECONDARY_USER_ID + 1);
        try {
            block(mSecondaryContext, NUMBER);
            assertEquals(BlockedNumberContract.STATUS_BLOCKED_IN_LIST,
                    SystemContract.shouldSystemBlockNumber(mSecondaryContext, NUMBER, null));
            SystemContract.notifyEmergencyContact(otherContext);

            assertTrue(SystemContract.getBlockSuppressionStatus(mSecondaryContext).isSuppressed);
            assertEquals(BlockedNumberContract.STATUS_NOT_BLOCKED,
                    SystemContract.shouldSystemBlockNumber(mSecondaryContext, NUMBER, null));
            assertFalse(BlockedNumberContract.isBlocked(otherContext, NUMBER));

            SystemContract.endBlockSuppression(mSecondaryContext);
            assertFalse(SystemContract.getBlockSuppressionStatus(otherContext).isSuppressed);
            assertEquals(BlockedNumberContract.STATUS_BLOCKED_IN_LIST,
                    SystemContract.shouldSystemBlockNumber(mSecondaryContext, NUMBER, null));
        } finally {
            otherContext.shutdown();
        }
    }

    public void testExistingSystemSuppressionPreferenceIsRetained() {
        long until = System.currentTimeMillis() + 60_000;
        mSecondaryContext.getSharedPreferences("block_number_provider_prefs", 0).edit()
                .putLong("block_suppression_expiry_time_pref", until).commit();
        SystemContract.BlockSuppressionStatus status =
                SystemContract.getBlockSuppressionStatus(mSecondaryContext);
        assertTrue(status.isSuppressed);
        assertEquals(until, status.untilTimestampMillis);
    }

    public void testUnavailableSuppressionStateDoesNotRejectCallback() {
        block(mSecondaryContext, NUMBER);
        doReturn(PackageManager.PERMISSION_DENIED).when(mSecondaryContext)
                .checkCallingOrSelfPermission(BlockSuppressionProvider.ACCESS_PERMISSION);
        assertEquals(BlockedNumberContract.STATUS_NOT_BLOCKED,
                SystemContract.shouldSystemBlockNumber(mSecondaryContext, NUMBER, null));
        // Reading a local list remains independent of suppression-service availability.
        assertTrue(BlockedNumberContract.isBlocked(mSecondaryContext, NUMBER));
    }

    public void testInternalSuppressionEndpointRequiresSignaturePermission() {
        doReturn(PackageManager.PERMISSION_DENIED).when(mSecondaryContext)
                .checkCallingOrSelfPermission(BlockSuppressionProvider.ACCESS_PERMISSION);
        expectSecurityException(() -> mSecondaryContext.getContentResolver().call(
                BlockSuppressionProvider.CONTENT_URI,
                SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT, null, null));
    }

    public void testInternalSuppressionEndpointDoesNotExposeBlockedRows() {
        try {
            mSecondaryContext.getContentResolver().call(BlockSuppressionProvider.CONTENT_URI,
                    BlockedNumberContract.METHOD_IS_BLOCKED, NUMBER, null);
            fail("The suppression endpoint must not implement blocked-number queries");
        } catch (IllegalArgumentException expected) {
        }
    }

    private MyMockContext newSecondaryContext(int userId) {
        MyMockContext context = spy(new MyMockContext(getContext()));
        context.initializeContext();
        doReturn(userId).when(context).getUserId();
        when(context.getSystemService(UserManager.class)).thenReturn(context.mUserManager);
        when(context.mUserManager.isUserOfType(UserManager.USER_TYPE_FULL_SECONDARY))
                .thenReturn(true);
        when(context.mCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_LOCATION));
        when(context.mAppOpsManager.noteOp(eq(AppOpsManager.OP_WRITE_SMS), anyInt(), anyString()))
                .thenReturn(AppOpsManager.MODE_ERRORED);
        return context;
    }

    private void block(MyMockContext context, String number) {
        ContentValues values = new ContentValues();
        values.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number);
        assertNotNull(context.getContentResolver().insert(BlockedNumbers.CONTENT_URI, values));
    }

    private void assertRowCount(MyMockContext context, int count) {
        try (Cursor cursor = context.getContentResolver().query(
                BlockedNumbers.CONTENT_URI, null, null, null, null)) {
            assertNotNull(cursor);
            assertEquals(count, cursor.getCount());
        }
    }

    private void expectSecurityException(Runnable operation) {
        try {
            operation.run();
            fail("SecurityException expected");
        } catch (SecurityException expected) {
        }
    }
}
