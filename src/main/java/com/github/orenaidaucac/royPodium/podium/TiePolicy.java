package com.github.orenaidaucac.royPodium.podium;

/**
 * Controls how duplicate players / tied ranks are handled during a podium payout.
 */
public enum TiePolicy {
    /** Every rank slot is rewarded independently. */
    ALLOW_TIES,
    /** Each player UUID can only be rewarded once per podium payout (highest rank wins). */
    SKIP_DUPLICATE_PLAYER
}
