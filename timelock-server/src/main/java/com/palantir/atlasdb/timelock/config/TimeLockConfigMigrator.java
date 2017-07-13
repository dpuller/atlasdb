/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 */

package com.palantir.atlasdb.timelock.config;

import com.google.common.base.Preconditions;
import com.palantir.remoting2.config.service.ServiceConfiguration;
import com.palantir.timelock.config.ImmutableClusterConfiguration;
import com.palantir.timelock.config.ImmutablePaxosInstallConfiguration;
import com.palantir.timelock.config.ImmutablePaxosRuntimeConfiguration;
import com.palantir.timelock.config.ImmutableTimeLockInstallConfiguration;
import com.palantir.timelock.config.ImmutableTimeLockRuntimeConfiguration;
import com.palantir.timelock.config.TimeLockInstallConfiguration;
import com.palantir.timelock.config.TimeLockRuntimeConfiguration;

public class TimeLockConfigMigrator {
    private TimeLockConfigMigrator() { /* Utility Class */ }

    public static CombinedTimeLockServerConfiguration convert(TimeLockServerConfiguration config) {
        // taking advantage of the fact that there is only one algorithm impl at the moment
        Preconditions.checkArgument(PaxosConfiguration.class.isInstance(config.algorithm()),
                "Paxos is the only leader election algorithm currently supported. Not: %s",
                config.algorithm().getClass());
        PaxosConfiguration paxos = (PaxosConfiguration) config.algorithm();

        TimeLockInstallConfiguration install = ImmutableTimeLockInstallConfiguration.builder()
                .algorithm(ImmutablePaxosInstallConfiguration.builder()
                        .dataDirectory(paxos.paxosDataDir())
                        .build())
                .cluster(ImmutableClusterConfiguration.builder()
                        .cluster(ServiceConfiguration.builder()
                                .security(paxos.sslConfiguration())
                                .uris(config.cluster().servers())
                                .build())
                        .localServer(config.cluster().localServer())
                        .build())
                .build();

        TimeLockRuntimeConfiguration runtime = ImmutableTimeLockRuntimeConfiguration.builder()
                .algorithm(ImmutablePaxosRuntimeConfiguration.builder()
                        .leaderPingResponseWaitMs(paxos.leaderPingResponseWaitMs())
                        .maximumWaitBeforeProposalMs(paxos.maximumWaitBeforeProposalMs())
                        .pingRateMs(paxos.pingRateMs())
                        .build())
                .clients(config.clients())
                .slowLockLogTriggerMillis(config.slowLockLogTriggerMillis())
                .build();

        return ImmutableCombinedTimeLockServerConfiguration.builder()
                .install(install)
                .runtime(runtime)
                .build();
    }
}
