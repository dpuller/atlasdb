/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
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
package com.palantir.atlasdb.keyvalue.dbkvs.impl;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.palantir.atlasdb.keyvalue.dbkvs.impl.oracle.OverflowSequenceSupplier;
import com.palantir.nexus.db.sql.SqlConnection;

public class OverflowSequenceSupplierTest {

    @Test
    public void shouldGetConsecutiveOverflowIdsFromSameSupplier() throws Exception {
        final ConnectionSupplier conns = mock(ConnectionSupplier.class);
        final SqlConnection sqlConnection = mock(SqlConnection.class);

        when(conns.get()).thenReturn(sqlConnection);
        when(sqlConnection.selectIntegerUnregisteredQuery(anyString())).thenReturn(1);

        OverflowSequenceSupplier sequenceSupplier = OverflowSequenceSupplier.create(conns, "a_");
        long firstSequenceId = sequenceSupplier.get();
        long nextSequenceId = sequenceSupplier.get();

        assertThat(nextSequenceId - firstSequenceId, is(1L));
    }

    @Test
    public void shouldNotGetOverflowIdsWithOverlappingCachesFromDifferentSuppliers() throws Exception {
        final ConnectionSupplier conns = mock(ConnectionSupplier.class);
        final SqlConnection sqlConnection = mock(SqlConnection.class);

        when(conns.get()).thenReturn(sqlConnection);
        when(sqlConnection.selectIntegerUnregisteredQuery(anyString())).thenReturn(1, 1001);

        long firstSequenceId = OverflowSequenceSupplier.create(conns, "a_").get();
        long secondSequenceId = OverflowSequenceSupplier.create(conns, "a_").get();

        assertThat(secondSequenceId - firstSequenceId, greaterThanOrEqualTo(1000L));
    }

    @Test
    public void shouldSkipValuesReservedByOtherSupplier() throws Exception {
        final ConnectionSupplier conns = mock(ConnectionSupplier.class);
        final SqlConnection sqlConnection = mock(SqlConnection.class);

        when(conns.get()).thenReturn(sqlConnection);
        when(sqlConnection.selectIntegerUnregisteredQuery(anyString())).thenReturn(1, 1001, 2001);

        OverflowSequenceSupplier firstSupplier = OverflowSequenceSupplier.create(conns, "a_");
        firstSupplier.get(); // gets 1
        OverflowSequenceSupplier.create(conns, "a_").get(); // gets 1001

        // After 1000 gets from the first supplier, we should get to 1000
        long id = 0;
        for (int i = 0; i < 999; i++) {
            id = firstSupplier.get();
        }
        assertThat(id, equalTo(1000L));

        // Should then skip to 2001
        assertThat(firstSupplier.get(), equalTo(2001L));
    }
}
