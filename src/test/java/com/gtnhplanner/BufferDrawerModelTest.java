package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Drawer.Kind;
import com.gtnhplanner.data.flowchart.Drawer.Link;
import com.gtnhplanner.data.flowchart.Drawer.Rule;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Serializer;

/**
 * A buffer drawer's data ({@code docs/design/buffer-drawers.md}): how a product or a source becomes one when a port of
 * the other side is linked, how it goes back to the end it still is, and that a save keeps it.
 */
class BufferDrawerModelTest {

    private static final UUID MAKER = UUID.nameUUIDFromBytes("maker".getBytes()),
        USER = UUID.nameUUIDFromBytes("user".getBytes()), OTHER = UUID.nameUUIDFromBytes("other".getBytes());

    @Test
    void aProductGivenAnInputIsABufferAndBackAgain() {
        final Drawer d = new Drawer(Kind.PRODUCT, "item:dust");
        d.setTarget(Rule.EXACTLY, 12);
        d.addPort(new Link(MAKER, 0), false);
        assertEquals(Kind.PRODUCT, d.getKind());
        assertTrue(d.addPort(new Link(USER, 1), true));
        assertEquals(Kind.BUFFER, d.getKind());
        assertEquals(List.of(new Link(MAKER, 0)), d.getLinks());
        assertEquals(List.of(new Link(USER, 1)), d.getTakers());
        assertTrue(d.holds(USER, 1, true));
        assertTrue(d.holds(MAKER, 0, false));
        assertFalse(d.holds(USER, 1, false));
        assertEquals(Rule.EXACTLY, d.effectiveRule(), "a buffer keeps its rule");
        assertTrue(d.isAnchor());

        assertTrue(d.removePort(new Link(USER, 1), true));
        assertEquals(Kind.PRODUCT, d.getKind(), "no users left: a product again");
        assertEquals(Rule.EXACTLY, d.getRule());
    }

    @Test
    void aSourceGivenAnOutputIsABufferWhoseUsersWereItsLinks() {
        final Drawer d = new Drawer(Kind.SOURCE, "item:dust");
        d.addPort(new Link(USER, 0), true);
        d.addPort(new Link(MAKER, 2), false);
        assertEquals(Kind.BUFFER, d.getKind());
        assertEquals(List.of(new Link(MAKER, 2)), d.getLinks());
        assertEquals(List.of(new Link(USER, 0)), d.getTakers());

        d.removePort(new Link(MAKER, 2), false);
        assertEquals(Kind.SOURCE, d.getKind(), "no makers left: a source again");
        assertEquals(List.of(new Link(USER, 0)), d.getLinks());
        assertTrue(
            d.getTakers()
                .isEmpty());
    }

    @Test
    void trashTakesNoInput() {
        final Drawer d = new Drawer(Kind.TRASH, "item:dust");
        assertFalse(d.takesSide(true));
        assertFalse(d.addPort(new Link(USER, 0), true));
        assertEquals(Kind.TRASH, d.getKind());
    }

    @Test
    void aPortHasOneDrawerEachWay() {
        final Graph g = new Graph("t");
        final Drawer a = new Drawer(Kind.PRODUCT, "item:dust"), b = new Drawer(Kind.SOURCE, "item:dust");
        g.addDrawer(a);
        g.addDrawer(b);
        g.linkDrawerPort(a.getId(), new Link(MAKER, 0), false);
        g.linkDrawerPort(b.getId(), new Link(USER, 0), true);
        // The user now draws from a, which makes it a buffer; b lets go of it.
        g.linkDrawerPort(a.getId(), new Link(USER, 0), true);
        assertEquals(Kind.BUFFER, a.getKind());
        assertEquals(g.drawerAt(USER, 0, true), a);
        assertTrue(
            b.getLinks()
                .isEmpty());
        // Removing the user's card lets the buffer be a product again.
        a.removeLinksTo(USER);
        assertEquals(Kind.PRODUCT, a.getKind());
    }

    @Test
    void aSaveKeepsABuffer() {
        final Graph g = new Graph("t");
        final Drawer d = new Drawer(Kind.PRODUCT, "item:dust");
        d.setTarget(Rule.AT_MOST, 3);
        g.addDrawer(d);
        g.linkDrawerPort(d.getId(), new Link(MAKER, 0), false);
        g.linkDrawerPort(d.getId(), new Link(USER, 1), true);
        g.linkDrawerPort(d.getId(), new Link(OTHER, 0), true);
        final Graph back = Serializer.decode(Serializer.encode(g));
        final Drawer again = back.getDrawer(d.getId());
        assertEquals(Kind.BUFFER, again.getKind());
        assertEquals(List.of(new Link(MAKER, 0)), again.getLinks());
        assertEquals(List.of(new Link(USER, 1), new Link(OTHER, 0)), again.getTakers());
        assertEquals(Rule.AT_MOST, again.getRule());
        assertEquals(3, again.getRate(), 1e-9);
    }
}
