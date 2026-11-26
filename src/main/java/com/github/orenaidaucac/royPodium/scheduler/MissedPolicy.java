package com.github.orenaidaucac.royPodium.scheduler;

/**
 * Policy for handling scheduled payouts missed while the server was offline.
 */
public enum MissedPolicy {
    SKIP,
    RUN_LATEST,
    RUN_ALL
}
