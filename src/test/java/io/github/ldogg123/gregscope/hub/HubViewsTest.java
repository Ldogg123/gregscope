package io.github.ldogg123.gregscope.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** GS-112: the open-view cap of design-v0.2 section 9.1 over {@code limits.maxOpenHubViews} (section 12.3). */
class HubViewsTest {

    private static UUID viewer(int n) {
        return new UUID(0L, n);
    }

    @Test
    void anEmptyRegisterAllowsAnyoneAndCountsNothing() {
        HubViews views = new HubViews();
        assertEquals(0, views.size());
        assertTrue(views.canOpen(viewer(1), 32));
        assertFalse(views.isOpen(viewer(1)));
        // Asking never records anything: only opened() does.
        assertEquals(0, views.size());
    }

    @Test
    void theCapIsTheNumberOfOpenViews() {
        HubViews views = new HubViews();
        for (int i = 0; i < 3; i++) {
            assertTrue(views.canOpen(viewer(i), 3), "viewer " + i);
            assertTrue(views.opened(viewer(i)));
        }
        assertEquals(3, views.size());
        assertFalse(views.canOpen(viewer(99), 3), "the fourth viewer is refused at cap 3");
        views.closed(viewer(0));
        assertEquals(2, views.size());
        assertTrue(views.canOpen(viewer(99), 3), "a closed view frees the slot");
    }

    /** A player who already has a view open can always open another one; they hold one slot, not two. */
    @Test
    void aViewerWithAnOpenViewIsNeverRefused() {
        HubViews views = new HubViews();
        views.opened(viewer(1));
        assertFalse(views.canOpen(viewer(2), 1), "the cap of 1 is taken");
        assertTrue(views.canOpen(viewer(1), 1), "the holder of the only view");
        assertFalse(views.opened(viewer(1)), "opening twice does not add a second view");
        assertEquals(1, views.size());
    }

    @Test
    void closingWhatIsNotOpenChangesNothing() {
        HubViews views = new HubViews();
        assertFalse(views.closed(viewer(1)));
        assertFalse(views.closed(null));
        views.opened(viewer(1));
        assertTrue(views.closed(viewer(1)));
        assertFalse(views.closed(viewer(1)));
        assertEquals(0, views.size());
    }

    @Test
    void aViewerWithoutUuidCanNeverOpen() {
        HubViews views = new HubViews();
        assertFalse(views.canOpen(null, 32));
        assertFalse(views.isOpen(null));
    }

    @Test
    void aCapOfZeroOrLessAllowsNothingNew() {
        HubViews views = new HubViews();
        assertFalse(views.canOpen(viewer(1), 0));
        assertFalse(views.canOpen(viewer(1), -1));
        views.opened(viewer(1));
        assertTrue(views.canOpen(viewer(1), 0), "an already open view stays open");
    }

    @Test
    void clearForgetsEveryView() {
        HubViews views = new HubViews();
        views.opened(viewer(1));
        views.opened(viewer(2));
        assertEquals(2, views.size());
        views.clear();
        assertEquals(0, views.size());
        assertFalse(views.isOpen(viewer(1)));
    }

    @Test
    void openedRejectsANullViewer() {
        HubViews views = new HubViews();
        try {
            views.opened(null);
            org.junit.jupiter.api.Assertions.fail("a null viewer was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("viewer", expected.getMessage());
        }
    }
}
