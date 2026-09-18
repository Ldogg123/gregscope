package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

/**
 * The Telemetry Hub's header and footer line (design-v0.2 section 9.2), as it travels through {@code gs_header}.
 * Immutable. [pure]
 *
 * <p>
 * Section 9.2's header is "Telemetry Hub {@code .} &lt;owner&gt;'s team {@code .} 23 sensors (20 live)
 * [All|Problems]" and its last line is "Sampler 0.41 ms/tick p99 {@code .} skipped 0". Both are one DTO: they are
 * built from the same frame, they change together, and one {@code GenericSyncValue} is one comparison per rebuild
 * instead of two.
 *
 * <p>
 * {@link #total()} counts the sensors in the Hub owner's scope; {@link #shown()} counts what is left after the
 * Problems filter and is what {@link #pages()} is computed from. When the two differ the client knows the filter is
 * hiding something without having to count rows it never received.
 */
public final class HubHeader {

    /** The header of a Hub with no owner and no data; also what a viewer sees before the first frame. */
    public static final HubHeader EMPTY = new HubHeader(
        null,
        "",
        0,
        0,
        0,
        HubViewModel.FILTER_ALL,
        0,
        1,
        false,
        true,
        0L,
        0L,
        0);

    private final UUID owner;
    private final String ownerName;
    private final int total;
    private final int live;
    private final int shown;
    private final byte filter;
    private final int page;
    private final int pages;
    private final boolean unsupported;
    private final boolean samplingEnabled;
    private final long cycleMicrosP99;
    private final long samplingSkippedTotal;
    private final int sensorsAbandoned;

    public HubHeader(UUID owner, String ownerName, int total, int live, int shown, int filter, int page, int pages,
        boolean unsupported, boolean samplingEnabled, long cycleMicrosP99, long samplingSkippedTotal,
        int sensorsAbandoned) {
        this.owner = owner;
        this.ownerName = HubCodecs.cap(ownerName, HubCodecs.MAX_OWNER_NAME);
        this.total = total;
        this.live = live;
        this.shown = shown;
        this.filter = (byte) filter;
        this.page = page;
        this.pages = pages;
        this.unsupported = unsupported;
        this.samplingEnabled = samplingEnabled;
        this.cycleMicrosP99 = cycleMicrosP99;
        this.samplingSkippedTotal = samplingSkippedTotal;
        this.sensorsAbandoned = sensorsAbandoned;
    }

    /** The Hub owner's UUID, or null for an unowned or unsupported Hub. */
    public UUID owner() {
        return owner;
    }

    /** The cached owner name, capped at {@link HubCodecs#MAX_OWNER_NAME}; empty when there is none. */
    public String ownerName() {
        return ownerName;
    }

    /** Sensors in the Hub owner's scope (section 5), before the Problems filter. */
    public int total() {
        return total;
    }

    /** How many of {@link #total()} are LIVE. */
    public int live() {
        return live;
    }

    /** Sensors left after the filter: what the pages hold. */
    public int shown() {
        return shown;
    }

    /** {@link HubViewModel#FILTER_ALL} or {@link HubViewModel#FILTER_PROBLEMS}. */
    public byte filter() {
        return filter;
    }

    /** The current page, 0-based. */
    public int page() {
        return page;
    }

    /** At least 1, so an empty Hub still reads "1 / 1". */
    public int pages() {
        return pages;
    }

    /** The tile entity's {@code gsHub} record is newer than this build understands (section 9.1). */
    public boolean unsupported() {
        return unsupported;
    }

    /** {@code sampling.enabled}; false means every live sensor is recording gaps on purpose. */
    public boolean samplingEnabled() {
        return samplingEnabled;
    }

    /** The sampler's p99 cycle cost in microseconds (section 7.6). */
    public long cycleMicrosP99() {
        return cycleMicrosP99;
    }

    /** Samples skipped by the tick budget or by {@code sampling.enabled=false}, since the server started. */
    public long samplingSkippedTotal() {
        return samplingSkippedTotal;
    }

    /**
     * Sensors whose history file failed {@code HistoryPersistence.MAX_FILE_FAILURES} times and was given up on for
     * the rest of the run. Their rings still hold history; nothing new reaches the disk. 0 on a healthy server, and
     * the one number that says the Hub's 24-hour lines will not survive a restart.
     */
    public int sensorsAbandoned() {
        return sensorsAbandoned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HubHeader)) {
            return false;
        }
        HubHeader h = (HubHeader) o;
        return total == h.total && live == h.live
            && shown == h.shown
            && filter == h.filter
            && page == h.page
            && pages == h.pages
            && unsupported == h.unsupported
            && samplingEnabled == h.samplingEnabled
            && cycleMicrosP99 == h.cycleMicrosP99
            && samplingSkippedTotal == h.samplingSkippedTotal
            && sensorsAbandoned == h.sensorsAbandoned
            && (owner == null ? h.owner == null : owner.equals(h.owner))
            && ownerName.equals(h.ownerName);
    }

    @Override
    public int hashCode() {
        int h = owner == null ? 0 : owner.hashCode();
        h = h * 31 + ownerName.hashCode();
        h = h * 31 + total;
        h = h * 31 + live;
        h = h * 31 + shown;
        h = h * 31 + filter;
        h = h * 31 + page;
        h = h * 31 + pages;
        h = h * 31 + (unsupported ? 2 : 0) + (samplingEnabled ? 1 : 0);
        h = h * 31 + (int) (cycleMicrosP99 ^ (cycleMicrosP99 >>> 32));
        h = h * 31 + (int) (samplingSkippedTotal ^ (samplingSkippedTotal >>> 32));
        return h * 31 + sensorsAbandoned;
    }

    @Override
    public String toString() {
        return "HubHeader{" + total
            + " sensors ("
            + live
            + " live), shown "
            + shown
            + ", page "
            + (page + 1)
            + "/"
            + pages
            + ", filter "
            + filter
            + (unsupported ? ", unsupported" : "")
            + "}";
    }
}
