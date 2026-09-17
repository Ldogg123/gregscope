package io.github.ldogg123.gregscope.history;

/** Read access to minute history, as passed to readers such as {@link GapRanges} and {@link Summaries}. [pure] */
public interface MinuteSource {

    /** The valid slot recorded for {@code epochMinute}, or {@code null} if that minute has no valid slot. */
    MinuteSlot slot(int epochMinute);
}
