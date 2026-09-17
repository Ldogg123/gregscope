package io.github.ldogg123.gregscope.sampling;

/** A settable {@link Clock} for unit tests and the Horizon-QA test hooks (design-v0.2 §13.1). Thread-safe. [pure] */
public final class FakeClock implements Clock {

    private volatile long epochMillis;

    public FakeClock(long epochMillis) {
        this.epochMillis = epochMillis;
    }

    public static FakeClock atEpochSec(long epochSec) {
        return new FakeClock(epochSec * 1000L);
    }

    @Override
    public long epochMillis() {
        return epochMillis;
    }

    public synchronized void setEpochMillis(long millis) {
        epochMillis = millis;
    }

    public synchronized void advanceMillis(long millis) {
        epochMillis += millis;
    }

    public void advanceSeconds(long seconds) {
        advanceMillis(seconds * 1000L);
    }
}
