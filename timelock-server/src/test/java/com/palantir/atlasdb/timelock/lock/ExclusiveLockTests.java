/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.timelock.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.Completableresult;

import org.junit.Test;

import com.palantir.atlasdb.timelock.FakeDelayedCanceller;

public class ExclusiveLockTests {

    private static final UUID REQUEST_1 = UUID.randomUUID();
    private static final UUID REQUEST_2 = UUID.randomUUID();
    private static final UUID REQUEST_3 = UUID.randomUUID();

    private static final long DEADLINE = 123L;

    private final FakeDelayedCanceller canceller = new FakeDelayedCanceller();
    private final ExclusiveLock lock = new ExclusiveLock(canceller);

    @Test
    public void canLockAndUnlock() {
        lockSynchronously(REQUEST_1);
        lock.unlock(REQUEST_1);
    }

    @Test
    public void lockIsExclusive() {
        lockSynchronously(REQUEST_1);

        AsyncResult<Void> result = lockAsync(REQUEST_2);
        assertThat(result.isComplete()).isFalse();
    }

    @Test
    public void lockCanBeObtainedAfterBeingUnlocked() {
        lockSynchronously(REQUEST_1);
        lock.unlock(REQUEST_1);

        lockSynchronously(REQUEST_2);
    }

    @Test
    public void queuedRequestObtainsLockAfterBeingUnlocked() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> result = lockAsync(REQUEST_2);

        unlock(REQUEST_1);

        assertThat(result.isCompletedSuccessfully()).isTrue();
    }

    @Test
    public void multipleQueuedRequestsCanObtainLock() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> result2 = lockAsync(REQUEST_2);
        AsyncResult<Void> result3 = lockAsync(REQUEST_3);

        unlock(REQUEST_1);

        assertThat(result2.isCompletedSuccessfully()).isTrue();
        assertThat(result3.isComplete()).isFalse();

        unlock(REQUEST_2);

        assertThat(result3.isCompletedSuccessfully()).isTrue();
    }

    @Test
    public void unlockByNonHolderNoOps() {
        lockSynchronously(REQUEST_1);

        unlock(UUID.randomUUID());
        assertThat(lock.getCurrentHolder()).isEqualTo(REQUEST_1);
    }

    @Test
    public void unlockByWaiterNoOps() {
        lockSynchronously(REQUEST_1);

        AsyncResult<Void> request2 = lockAsync(REQUEST_2);
        unlock(REQUEST_2);

        assertThat(lock.getCurrentHolder()).isEqualTo(REQUEST_1);
        assertThat(request2.isComplete()).isFalse();
        // request2 should still get the lock when it's available
        unlock(REQUEST_1);
        assertCompletedSuccessfully(request2);
    }

    @Test
    public void lockIsAcquiredSynchronouslyIfAvailable() {
        AsyncResult<Void> result = lock.lock(REQUEST_1, DEADLINE);
        assertTrue(result.isComplete());
    }

    @Test
    public void waitUntilAvailableCompletesSynchronouslyIfAvailable() {
        AsyncResult<Void> result = lock.waitUntilAvailable(REQUEST_1, DEADLINE);
        assertTrue(result.isComplete());
    }

    @Test
    public void waitUntilAvailableWantsUntilLockIsFree() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> result = waitUntilAvailableAsync(REQUEST_2);

        assertThat(result.isComplete()).isFalse();

        unlock(REQUEST_1);

        assertThat(result.isCompletedSuccessfully()).isTrue();
    }

    @Test
    public void waitUntilAvailableDoesNotBlockLockRequests() {
        lockSynchronously(REQUEST_1);
        waitUntilAvailableAsync(REQUEST_2);
        AsyncResult<Void> lockRequest = lockAsync(REQUEST_3);

        assertThat(lockRequest.isComplete()).isFalse();

        unlock(REQUEST_1);

        assertThat(lockRequest.isCompletedSuccessfully()).isTrue();
    }

    @Test
    public void multipleWaitUntilAvailableRequestsAllCompleteWhenLockIsFree() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> request2 = waitUntilAvailableAsync(REQUEST_2);
        AsyncResult<Void> request3 = lock.waitUntilAvailable(REQUEST_3, DEADLINE);

        unlock(REQUEST_1);

        assertThat(request2.isCompletedSuccessfully()).isTrue();
        assertThat(request3.isCompletedSuccessfully()).isTrue();
    }

    @Test
    public void lockRequestIsCancelledAfterDeadline() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> request2 = lockAsync(REQUEST_2);

        canceller.tick(DEADLINE + 1);

        assertThat(request2.isTimedOut()).isTrue();
    }

    @Test
    public void waitRequestIsCancelledAfterDeadline() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> request2 = waitUntilAvailableAsync(REQUEST_1);

        canceller.tick(DEADLINE + 1);

        assertThat(request2.isTimedOut()).isTrue();
    }

    @Test
    public void timedOutLockRequestDoesNotGetTheLock() {
        lockSynchronously(REQUEST_1);
        AsyncResult<Void> request2 = lockAsync(REQUEST_2);

        canceller.tick(DEADLINE + 1);
        unlock(REQUEST_1);

        assertThat(lock.getCurrentHolder()).isNull();
        lockSynchronously(REQUEST_1);
    }

    @Test
    public void availableLockIsAcquiredEvenIfDeadlineIsAlreadyPast() {
        canceller.setShouldCancelSynchronously(true);
        lockSynchronously(REQUEST_1);
    }

    @Test
    public void availableLockIsAvailableIfDeadlineIsAlreadyPast() {
        canceller.setShouldCancelSynchronously(true);
        waitUntilAvailableSynchronously(REQUEST_1);
    }

    private AsyncResult<Void> waitUntilAvailableAsync(UUID request) {
        return lock.waitUntilAvailable(request, DEADLINE);
    }

    private void waitUntilAvailableSynchronously(UUID requestId) {
        waitUntilAvailableAsync(requestId).get();
    }

    private void lockSynchronously(UUID requestId) {
        lock.lock(requestId, DEADLINE).get();
    }

    private AsyncResult<Void> lockAsync(UUID requestId) {
        return lock.lock(requestId, DEADLINE);
    }

    private void unlock(UUID requestId) {
        lock.unlock(requestId);
    }

}
