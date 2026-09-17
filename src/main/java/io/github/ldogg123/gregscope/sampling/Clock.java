package io.github.ldogg123.gregscope.sampling;

/**
 * Wall-clock source for history timestamps (design-v0.2 §6.2): {@link #SYSTEM} in production, {@link FakeClock}
 * through the test hooks. [pure]
 */
public interface Clock {

    /** The system wall clock. */
    Clock SYSTEM = System::currentTimeMillis;

    /** Milliseconds since the Unix epoch. */
    long epochMillis();

    /** Seconds since the Unix epoch, rounded down. */
    default long epochSec() {
        return Math.floorDiv(epochMillis(), 1000L);
    }

    /** Minutes since the Unix epoch, rounded down. */
    default long epochMinute() {
        return Math.floorDiv(epochMillis(), 60_000L);
    }
}
