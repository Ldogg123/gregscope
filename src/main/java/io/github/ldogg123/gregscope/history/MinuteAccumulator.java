package io.github.ldogg123.gregscope.history;

import java.math.BigInteger;
import java.util.Arrays;

import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * Folds one sensor's samples and gap seconds into the open minute and closes it into a {@link MinuteSlot} (design-v0.2
 * §6.2, §7.4). Server thread only. [pure]
 *
 * <p>
 * Minutes are {@code floorDiv(epochSec, 60)}. A sample for a later minute closes the open minute first and returns it.
 * A sample whose minute is older than the open minute is folded into the open minute (the wall clock went backwards).
 * With no minute open, a sample older than the newest closed or written minute is refused and counted in
 * {@link #clockSkewRefused()}; a sample for exactly that minute reopens it, and {@link MinuteRing#put} merges the two
 * pieces.
 */
public final class MinuteAccumulator {

    private final int expectedSamples;
    private final int serverStartEpochMinute;

    private boolean open;
    private int minute;
    private int samples;
    private int gapMask;
    private int lastStateCode;
    private final int[] stateSamples = new int[StateCodes.COUNT];
    private int maintenanceMax;
    private int flags;
    private int serverTicks;
    private int euSamples;
    /** Unsaturated EU sample count, the divisor of the average. */
    private long euCount;
    private long euSum;
    private BigInteger euSumBig;
    private long euMin;
    private long euMax;
    private long energyLast;
    private int recipesDelta;

    /** Last {@code recipesCompleted} seen, carried across minutes; -1 = no baseline. */
    private long recipesBaseline = -1;
    private boolean lastSampleResetRecipes;
    /** Newest minute closed here or already written (for example loaded from disk); 0 = none. */
    private int newestWritten;
    private long clockSkewRefused;

    /**
     * @param intervalTicks          sampling interval, giving {@code expectedSamples = 1200 / intervalTicks}
     * @param serverStartEpochMinute the minute this server run started; that minute's slot gets
     *                               {@link MinuteSlot#FLAG_SERVER_START_MINUTE}
     */
    public MinuteAccumulator(int intervalTicks, int serverStartEpochMinute) {
        this.expectedSamples = MinuteSlot.expectedSamples(intervalTicks);
        this.serverStartEpochMinute = serverStartEpochMinute;
    }

    public static int epochMinute(long epochSec) {
        long m = Math.floorDiv(epochSec, 60L);
        if (m == 0 || m > Integer.MAX_VALUE || m < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("epochSec out of range: " + epochSec);
        }
        return (int) m;
    }

    public boolean isOpen() {
        return open;
    }

    /** The open minute; only meaningful while {@link #isOpen()}. */
    public int openEpochMinute() {
        return minute;
    }

    public int newestWrittenEpochMinute() {
        return newestWritten;
    }

    /** Raises the newest written minute, for example after history was loaded from disk. */
    public void noteWritten(int epochMinute) {
        if (newestWritten == 0 || epochMinute > newestWritten) {
            newestWritten = epochMinute;
        }
    }

    /** Samples refused because their minute was older than the newest written minute. */
    public long clockSkewRefused() {
        return clockSkewRefused;
    }

    /** True if the last accepted sample saw {@code recipesCompleted} decrease (the delta step was 0). */
    public boolean lastSampleResetRecipes() {
        return lastSampleResetRecipes;
    }

    /**
     * Folds one probe sample.
     *
     * @param stateCode         {@link StateCodes} code
     * @param maintenanceIssues saturated to 255
     * @param hasEu             whether {@code euPerTick} is valid
     * @param hasEnergy         whether {@code energyStored} is valid
     * @param recipesCompleted  GT's lifetime counter, or negative if the machine is not a multiblock
     * @return the minute this sample closed, or {@code null}
     */
    public MinuteSlot sample(long epochSec, int stateCode, int maintenanceIssues, boolean hasEu, long euPerTick,
        boolean hasEnergy, long energyStored, long recipesCompleted) {
        if (!StateCodes.isKnown(stateCode)) {
            throw new IllegalArgumentException("stateCode " + stateCode);
        }
        lastSampleResetRecipes = false;
        int m = epochMinute(epochSec);
        MinuteSlot closed = advanceTo(m);
        if (!open) {
            return closed;
        }
        samples = LongMath.addU8(samples, 1);
        stateSamples[stateCode] = LongMath.addU8(stateSamples[stateCode], 1);
        lastStateCode = stateCode;
        maintenanceMax = Math.max(maintenanceMax, Math.min(LongMath.U8_MAX, Math.max(0, maintenanceIssues)));
        if (hasEu) {
            long eu = euPerTick == Long.MIN_VALUE ? Long.MIN_VALUE + 1 : euPerTick;
            if (euSamples == 0) {
                euMin = eu;
                euMax = eu;
            } else {
                euMin = Math.min(euMin, eu);
                euMax = Math.max(euMax, eu);
            }
            euSamples = LongMath.addU8(euSamples, 1);
            euCount++;
            addEu(eu);
        }
        if (hasEnergy) {
            energyLast = energyStored == Long.MIN_VALUE ? Long.MIN_VALUE + 1 : energyStored;
        }
        if (recipesCompleted >= 0) {
            if (recipesDelta < 0) {
                recipesDelta = 0;
            }
            if (recipesBaseline >= 0) {
                if (recipesCompleted < recipesBaseline) {
                    flags |= MinuteSlot.FLAG_RECIPES_COUNTER_RESET;
                    lastSampleResetRecipes = true;
                } else {
                    long step = recipesCompleted - recipesBaseline;
                    recipesDelta = (int) Math.min(Integer.MAX_VALUE, recipesDelta + step);
                }
            }
            recipesBaseline = recipesCompleted;
        }
        return closed;
    }

    /**
     * Records a gap second with a stored reason.
     *
     * @return the minute this call closed, or {@code null}
     */
    public MinuteSlot gap(long epochSec, GapReason reason) {
        requireStored(reason);
        lastSampleResetRecipes = false;
        MinuteSlot closed = advanceTo(epochMinute(epochSec));
        if (open) {
            gapMask |= reason.mask();
        }
        return closed;
    }

    /** Adds server ticks seen while the minute is open (u16, saturating); ignored while no minute is open. */
    public void addServerTicks(int ticks) {
        if (open && ticks > 0) {
            serverTicks = LongMath.addU16(serverTicks, ticks);
        }
    }

    /**
     * Closes the open minute at its boundary if it is older than {@code currentEpochMinute} (the per-interval sweep of
     * §6.1).
     */
    public MinuteSlot closeBefore(int currentEpochMinute) {
        return open && minute < currentEpochMinute ? close(0) : null;
    }

    /**
     * Closes the open minute early (unload, removal, stop) with {@link MinuteSlot#FLAG_PARTIAL_MINUTE} and, if given,
     * a stored gap reason. Returns {@code null} if no minute is open.
     */
    public MinuteSlot closePartial(GapReason reason) {
        if (reason != null) {
            requireStored(reason);
        }
        if (!open) {
            return null;
        }
        if (reason != null) {
            gapMask |= reason.mask();
        }
        return close(MinuteSlot.FLAG_PARTIAL_MINUTE);
    }

    private static void requireStored(GapReason reason) {
        if (reason == null || !reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
    }

    /** Makes {@code m} the open minute where allowed; returns a minute closed on the way. */
    private MinuteSlot advanceTo(int m) {
        if (open) {
            if (m <= minute) {
                return null;
            }
            MinuteSlot closed = close(0);
            openMinute(m);
            return closed;
        }
        if (newestWritten != 0 && m < newestWritten) {
            clockSkewRefused++;
            return null;
        }
        openMinute(m);
        return null;
    }

    private void openMinute(int m) {
        open = true;
        minute = m;
        samples = 0;
        gapMask = 0;
        lastStateCode = StateCodes.UNAVAILABLE;
        Arrays.fill(stateSamples, 0);
        maintenanceMax = 0;
        flags = m == serverStartEpochMinute ? MinuteSlot.FLAG_SERVER_START_MINUTE : 0;
        serverTicks = 0;
        euSamples = 0;
        euCount = 0;
        euSum = 0;
        euSumBig = null;
        euMin = MinuteSlot.NONE;
        euMax = MinuteSlot.NONE;
        energyLast = MinuteSlot.NONE;
        recipesDelta = MinuteSlot.RECIPES_NONE;
    }

    private void addEu(long eu) {
        if (euSumBig != null) {
            euSumBig = euSumBig.add(BigInteger.valueOf(eu));
            return;
        }
        long sum = euSum + eu;
        if (((euSum ^ sum) & (eu ^ sum)) < 0) {
            euSumBig = BigInteger.valueOf(euSum)
                .add(BigInteger.valueOf(eu));
        } else {
            euSum = sum;
        }
    }

    private MinuteSlot close(int extraFlags) {
        MinuteSlot.Builder b = MinuteSlot.builder(minute)
            .samples(samples)
            .expectedSamples(expectedSamples)
            .gapMask(gapMask)
            .lastStateCode(lastStateCode)
            .maintenanceMax(maintenanceMax)
            .flags(flags | extraFlags)
            .serverTicks(serverTicks)
            .euSamples(euSamples)
            .energyStoredLast(energyLast)
            .recipesCompletedDelta(recipesDelta);
        for (int i = 0; i < StateCodes.COUNT; i++) {
            b.stateSamples(i, stateSamples[i]);
        }
        if (euSamples > 0) {
            // euSamples saturates at 255 while the sum keeps every sample, so divide by the true count.
            BigInteger sum = euSumBig != null ? euSumBig : BigInteger.valueOf(euSum);
            long avg = LongMath.roundedDivide(sum, euCount);
            b.euPerTickAvg(avg)
                .euPerTickMin(euMin)
                .euPerTickMax(euMax);
        }
        open = false;
        noteWritten(minute);
        return b.build();
    }
}
