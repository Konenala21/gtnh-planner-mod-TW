package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.map.Boards;
import com.gtnhplanner.cloud.map.CloudGame;
import com.gtnhplanner.cloud.map.CloudPlans;
import com.gtnhplanner.cloud.map.TestGame;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.session.CloudSession;
import com.gtnhplanner.cloud.session.LiveDoc;
import com.gtnhplanner.cloud.store.CloudCache;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.library.CommunityApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The planner's plans while signed in, headless: a stand-in for the site (the account's list and plans) and one for
 * the live service (rooms that apply batches), the game's side played by {@link TestGame}. Edits the board commits go
 * out once, kept on disk first; others' edits are read into the board when it is at rest; undo takes back the player's
 * own; edits made offline survive a restart and go once back online; plans made, deleted and brought from a world.
 */
class CloudSessionTest {

    private static final String ME = "u1", TOKEN = "u1.9999999999999.sig";

    @TempDir
    File dir;

    private LiveTestServer live;
    private HttpServer site;
    private final String siteWas = CommunityApi.site();
    /** The account's plans as the site lists them, by design id. */
    private final Map<String, JsonObject> designs = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private CloudSession session;
    private final TestHost host = new TestHost();

    /** The game as the session sees it. */
    private static final class TestHost implements CloudSession.Host {

        final TestGame game = new TestGame();
        volatile boolean busy;
        final List<String> notices = new CopyOnWriteArrayList<>();
        final List<Graph[]> replaced = new ArrayList<>();
        @Nullable
        Plan inHand, world, worldSaved;

        @Override
        public CloudGame game() {
            return game;
        }

        @Override
        public LiveClient.Hello hello() {
            return new LiveClient.Hello("test", null, null);
        }

        @Override
        public boolean busy(final Graph graph) {
            return busy;
        }

        @Override
        public void replaced(final Graph old, final Graph next) {
            replaced.add(new Graph[] { old, next });
        }

        @Override
        public void notice(final String text) {
            notices.add(text);
        }

        @Override
        @Nullable
        public Plan inHand() {
            return inHand;
        }

        @Override
        @Nullable
        public Plan worldPlans() {
            return world;
        }

        @Override
        public boolean backUpWorld() {
            return true;
        }

        @Override
        public boolean saveWorld(final Plan plans) {
            worldSaved = plans;
            return true;
        }
    }

    @BeforeEach
    void start() throws Exception {
        live = new LiveTestServer(null);
        live.rooms = true;
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", this::answer);
        site.start();
        CommunityApi.useSite(
            "http://127.0.0.1:" + site.getAddress()
                .getPort());
        CloudApi.useLive("ws://127.0.0.1:" + live.port() + "/live");
    }

    @AfterEach
    void stop() {
        if (session != null) session.stop();
        live.close();
        site.stop(0);
        CommunityApi.useSite(siteWas);
        CloudApi.useLive(null);
    }

    // region The site

    private void answer(final HttpExchange ex) throws IOException {
        final String path = ex.getRequestURI()
            .getPath(), method = ex.getRequestMethod();
        final String body = new String(
            ex.getRequestBody()
                .readAllBytes(),
            StandardCharsets.UTF_8);
        calls.add(method + " " + path + (body.contains("\"plan\"") ? " with a plan" : ""));
        String reply = "{}";
        int status = 200;
        if (path.equals("/api/library") && method.equals("GET")) {
            final JsonObject o = new JsonObject();
            final JsonArray own = new JsonArray();
            for (final JsonObject d : designs.values()) own.add(d);
            o.add("designs", own);
            o.add("shared", new JsonArray());
            reply = o.toString();
        } else if (path.startsWith("/api/library/designs/")) {
            final String id = path.substring("/api/library/designs/".length());
            if (method.equals("GET")) {
                final JsonObject o = new JsonObject();
                o.add("design", designs.get(id));
                o.add("plan", live.plans.getOrDefault(ME + ":" + id, new JsonObject()));
                o.addProperty("seq", live.seqs.getOrDefault(ME + ":" + id, 0L));
                o.addProperty("role", "owner");
                reply = o.toString();
            } else if (method.equals("PUT")) {
                final JsonObject in = new JsonParser().parse(body)
                    .getAsJsonObject();
                if (in.has("plan")) live.plans.put(ME + ":" + id, in.getAsJsonObject("plan"));
                designs.put(
                    id,
                    design(
                        id,
                        in.get("name")
                            .getAsString()));
                final JsonObject o = new JsonObject();
                o.add("design", designs.get(id));
                reply = o.toString();
            } else if (method.equals("DELETE")) {
                final JsonObject d = designs.get(id);
                if (d != null) d.addProperty("deletedAt", "2026-10-14T12:00:00Z");
            } else status = 405;
        } else status = 404;
        final byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    private static JsonObject design(final String id, final String name) {
        final JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("name", name);
        d.addProperty("closed", false);
        d.addProperty("favorite", false);
        d.addProperty("createdAt", "2026-10-14T10:00:00Z");
        d.addProperty("updatedAt", "2026-10-14T10:00:00Z");
        d.addProperty("planUpdatedAt", "2026-10-14T10:00:00Z");
        d.add("members", new JsonArray());
        return d;
    }

    // endregion

    // region Helpers

    /** A plan in the account: a corpus board written as the website's plan, in its room at seq 3. */
    private Graph inAccount(final String id, final String corpus) {
        final Graph g = Boards.dress(Boards.corpus(corpus));
        host.game.knowing(g);
        live.plans.put(
            ME + ":" + id,
            CloudPlans.write(g, host.game, null, null)
                .plan());
        live.seqs.put(ME + ":" + id, 3L);
        designs.put(id, design(id, g.getName()));
        return g;
    }

    private Plan begin() {
        session = CloudSession.start(ME, () -> TOKEN, dir, host);
        final Plan p = session.load();
        host.inHand = p;
        return p;
    }

    private void tickUntil(final String what, final BooleanSupplier done) {
        final long until = System.currentTimeMillis() + 15_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > until)
                throw new AssertionError("timed out: " + what + "; the site heard " + calls);
            session.tick();
            try {
                Thread.sleep(5);
            } catch (final InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void ticks(final int n) {
        for (int i = 0; i < n; i++) {
            session.tick();
            try {
                Thread.sleep(5);
            } catch (final InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The slot holding a plan by doc key, as the planner has it now. */
    private static Graph slot(final Plan p, final String key) {
        for (final Graph g : p.getGraphs()) if (key.equals(g.getCloudKey())) return g;
        return null;
    }

    /** Opens a plan (its tab, the open one): the session joins its room and reads it in. */
    private Graph open(final Plan p, final String key) {
        tickUntil("the plan is listed", () -> slot(p, key) != null);
        p.setActiveIndex(
            p.getGraphs()
                .indexOf(slot(p, key)));
        session.save(p);
        tickUntil(
            "in the room with its body read",
            () -> session.doc(key) != null && session.doc(key)
                .inRoom()
                && !slot(p, key).getNodes()
                    .isEmpty()
                && !session.doc(key)
                    .behind());
        return slot(p, key);
    }

    private static JsonObject nodeIn(final JsonObject plan, final UUID id) {
        for (final JsonElement e : plan.getAsJsonArray("nodes")) if (id.toString()
            .equals(
                e.getAsJsonObject()
                    .get("id")
                    .getAsString()))
            return e.getAsJsonObject();
        return null;
    }

    private long opsFrames() {
        return live.framesOf("ops")
            .size();
    }

    // endregion

    @Test
    void theAccountsPlansOpenOnlyOnceItsSiteHasThem() {
        // A site without the live service (not rolled out, a stand-in): the world's plans stay open.
        CloudApi.useLive(
            "ws://127.0.0.1:" + site.getAddress()
                .getPort() + "/live");
        session = CloudSession.start(ME, () -> TOKEN, new File(dir, "before"), host);
        final boolean[] switched = { false };
        session.checkReady(() -> switched[0] = true);
        ticks(40);
        assertFalse(session.ready());
        assertFalse(switched[0]);
        session.stop();
        // The site has it: this device keeps that, and the planner switches over.
        CloudApi.useLive("ws://127.0.0.1:" + live.port() + "/live");
        session = CloudSession.start(ME, () -> TOKEN, dir, host);
        session.checkReady(() -> switched[0] = true);
        tickUntil("the site has cloud plans", () -> switched[0]);
        assertTrue(session.ready());
        session.stop();
        session = CloudSession.start(ME, () -> TOKEN, dir, host);
        assertTrue(session.ready(), "remembered on this device");
    }

    @Test
    void anEditGoesOutOnceAndOthersComeIn() {
        inAccount("d1", "sulfuric-acid");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        final LiveDoc doc = session.doc(board);
        assertNotNull(doc);
        assertEquals("owner", doc.role());
        assertEquals(CloudSession.Status.LIVE, session.status());

        // A card moved on the board: one batch, its place only, applied by the room.
        final Node moved = board.getNodes()
            .iterator()
            .next();
        final JsonObject before = nodeIn(live.plans.get(ME + ":d1"), moved.id);
        moved.x += 40;
        board.touchLayout();
        session.save(p);
        tickUntil("the room applied it", () -> live.seqs.get(ME + ":d1") == 4L && !doc.waiting());
        assertEquals(1, opsFrames(), "one batch");
        final JsonObject frame = live.framesOf("ops")
            .getFirst();
        assertEquals(
            1,
            frame.getAsJsonArray("ops")
                .size());
        final List<String> fields = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> e : frame.getAsJsonArray("ops")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("f")
            .entrySet()) fields.add(e.getKey());
        assertEquals(List.of("position"), fields, "a move is its place");
        assertNotEquals(
            before.get("position")
                .toString(),
            nodeIn(live.plans.get(ME + ":d1"), moved.id).get("position")
                .toString());

        // Someone else moves another card: the board reads it in, a new graph in the slot.
        final Node other = new ArrayList<>(board.getNodes()).get(1);
        final JsonObject at = new JsonObject();
        final JsonObject pos = new JsonObject();
        pos.addProperty("x", 999);
        pos.addProperty("y", 333);
        at.add("position", pos);
        final JsonArray theirs = new JsonArray();
        theirs.add(
            LiveOp.patch("nodes", other.id.toString(), at, null)
                .toJson());
        live.others(ME + ":d1", theirs);
        tickUntil("read in", () -> slot(p, ME + ":d1") != board);
        final Graph now = slot(p, ME + ":d1");
        assertEquals(com.gtnhplanner.library.PlanExport.gameX(999), now.nodes.get(other.id).x);
        assertEquals(moved.x, now.nodes.get(moved.id).x, "the player's own move stays");
        assertSame(
            now,
            session.doc(now)
                .graph());
        assertSame(board, host.replaced.getLast()[0]);
        ticks(10);
        assertEquals(1, opsFrames(), "reading others' edits sends nothing");

        // Undo takes back the player's own move only, as a new edit.
        assertTrue(session.undo(now));
        final Graph undone = slot(p, ME + ":d1");
        assertEquals(moved.x - 40, undone.nodes.get(moved.id).x);
        assertEquals(com.gtnhplanner.library.PlanExport.gameX(999), undone.nodes.get(other.id).x);
        tickUntil(
            "the undo went",
            () -> opsFrames() == 2 && !session.doc(undone)
                .waiting());
        assertFalse(session.undo(undone), "nothing more of the player's own");
    }

    @Test
    void othersEditsWaitWhileTheBoardIsBusy() {
        inAccount("d1", "glass");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        host.busy = true;
        final Node other = board.getNodes()
            .iterator()
            .next();
        final JsonObject at = new JsonObject();
        at.addProperty("machineCount", 7);
        final JsonArray theirs = new JsonArray();
        theirs.add(
            LiveOp.patch("nodes", other.id.toString(), at, null)
                .toJson());
        live.others(ME + ":d1", theirs);
        tickUntil(
            "landed on the live copy",
            () -> session.doc(ME + ":d1")
                .behind());
        ticks(20);
        assertSame(board, slot(p, ME + ":d1"), "not read while something is in hand");
        host.busy = false;
        tickUntil("read once at rest", () -> slot(p, ME + ":d1") != board);
    }

    @Test
    void editsMadeOfflineSurviveARestartAndGoOnceBackOnline() {
        inAccount("d1", "benzene");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        // The live service goes away: edits are kept on this device.
        CloudApi.useLive("ws://127.0.0.1:1/live");
        live.dropAll();
        final Node n = board.getNodes()
            .iterator()
            .next();
        n.y += 25;
        board.touchLayout();
        session.save(p);
        ticks(20);
        assertTrue(session.editsWaiting());
        assertEquals(3L, live.seqs.get(ME + ":d1"), "nothing went");
        session.stop();
        session = null;
        final CloudCache copy = CloudCache.forAccount(dir, ME);
        assertEquals(
            1,
            copy.pending(ME + ":d1")
                .size(),
            "the edit is on disk");
        copy.close();

        // The game starts again, back online: the edit goes once.
        CloudApi.useLive("ws://127.0.0.1:" + live.port() + "/live");
        final Plan again = begin();
        final Graph reopened = slot(again, ME + ":d1");
        assertNotNull(reopened, "opened from this device's copy");
        assertEquals(n.y, reopened.nodes.get(n.id).y, "with the edit on it");
        tickUntil("sent once back online", () -> live.seqs.get(ME + ":d1") == 4L && !session.editsWaiting());
        ticks(20);
        assertEquals(4L, live.seqs.get(ME + ":d1"), "applied once");
        assertEquals(
            com.gtnhplanner.library.PlanExport.position(n.x, n.y)
                .get("y")
                .getAsDouble(),
            nodeIn(live.plans.get(ME + ":d1"), n.id).getAsJsonObject("position")
                .get("y")
                .getAsDouble());
    }

    @Test
    void aNewPlanIsMadeInTheAccountAndADeletedOneLeavesIt() {
        inAccount("d1", "glass");
        final Plan p = begin();
        open(p, ME + ":d1");
        // A plan arrives (pasted, imported, new with a card on it): it becomes a cloud plan.
        final Graph made = Boards.corpus("hv-diesel");
        host.game.knowing(made);
        made.setName("Diesel");
        p.getGraphs()
            .add(made);
        session.save(p);
        assertNotNull(made.getCloudKey());
        final String id = made.getCloudKey()
            .substring(ME.length() + 1);
        tickUntil("made in the account", () -> designs.containsKey(id));
        assertEquals(
            "Diesel",
            designs.get(id)
                .get("name")
                .getAsString());
        assertEquals(
            made.getNodes()
                .size(),
            live.plans.get(made.getCloudKey())
                .getAsJsonArray("nodes")
                .size());
        // An empty new plan waits until it holds something.
        final Graph empty = new Graph("Empty");
        p.getGraphs()
            .add(empty);
        session.save(p);
        assertNull(empty.getCloudKey());

        // The player deletes the first plan: the account is told.
        final Graph first = slot(p, ME + ":d1");
        session.deleting(first);
        p.removeSlot(
            p.getGraphs()
                .indexOf(first));
        session.save(p);
        tickUntil("deleted in the account", () -> calls.contains("DELETE /api/library/designs/d1"));
        assertNull(session.doc(ME + ":d1"));
    }

    @Test
    void aWorldsPlansGoToTheAccountOnce() {
        final Plan world = Plan.cloud("unused");
        final Graph mine = Boards.corpus("mv");
        host.game.knowing(mine);
        mine.setName("MV line");
        world.getGraphs()
            .add(mine);
        host.world = world;
        final Plan p = begin();
        tickUntil("brought over", () -> host.notices.contains("Your plans from this world are now in your account"));
        assertNotNull(mine.getCloudKey(), "the world's slot is keyed");
        assertSame(world, host.worldSaved, "and the world's save kept the key");
        final Graph arrived = slot(p, mine.getCloudKey());
        assertNotNull(arrived, "the plan is in the planner");
        assertEquals(
            mine.getNodes()
                .size(),
            arrived.getNodes()
                .size());
        assertTrue(
            designs.containsKey(
                mine.getCloudKey()
                    .substring(ME.length() + 1)));
        // The next time, nothing goes again.
        session.stop();
        session = null;
        host.notices.clear();
        final int puts = (int) calls.stream()
            .filter(c -> c.startsWith("PUT ") && c.endsWith("with a plan"))
            .count();
        begin();
        ticks(60);
        assertEquals(
            puts,
            calls.stream()
                .filter(c -> c.startsWith("PUT ") && c.endsWith("with a plan"))
                .count(),
            "uploaded once");
        assertFalse(host.notices.contains("Your plans from this world are now in your account"));
    }

    @Test
    void aBatchTheServiceCallsTooFastStaysPendingAndGoesAgain() {
        inAccount("d1", "sulfuric-acid");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        final LiveDoc doc = session.doc(board);
        live.refuse = "rate";
        live.refuseTimes.set(1);
        final Node moved = board.getNodes()
            .iterator()
            .next();
        moved.x += 40;
        board.touchLayout();
        session.save(p);
        tickUntil("refused once", () -> live.refuseTimes.get() < 1);
        assertTrue(doc.waiting(), "kept pending");
        assertTrue(host.notices.isEmpty(), "not a lost edit");
        tickUntil("sent again and applied", () -> !doc.waiting());
        assertEquals(2, opsFrames(), "the same batch twice");
        assertEquals(
            live.framesOf("ops")
                .get(0)
                .get("cid"),
            live.framesOf("ops")
                .get(1)
                .get("cid"));
    }

    @Test
    void aBatchTheServiceCallsBadIsDroppedAndTold() {
        inAccount("d1", "sulfuric-acid");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        final LiveDoc doc = session.doc(board);
        live.refuse = "bad_op";
        final Node moved = board.getNodes()
            .iterator()
            .next();
        moved.x += 40;
        board.touchLayout();
        session.save(p);
        tickUntil("dropped", () -> !doc.waiting() && !host.notices.isEmpty());
        assertEquals(1, opsFrames(), "never sent again");
    }

    @Test
    void anEchoThatDiffersFromWhatWasSentIsReadIntoTheBoard() {
        inAccount("d1", "sulfuric-acid");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        final LiveDoc doc = session.doc(board);
        // The service stores the schema's output: here, a position nudged by one.
        live.correct = ops -> {
            final JsonArray out = new JsonArray();
            for (final JsonElement e : ops) {
                final JsonObject op = new JsonParser().parse(e.toString())
                    .getAsJsonObject();
                if (op.has("f") && op.getAsJsonObject("f")
                    .has("position"))
                    op.getAsJsonObject("f")
                        .getAsJsonObject("position")
                        .addProperty("x", 777);
                out.add(op);
            }
            return out;
        };
        final Node moved = board.getNodes()
            .iterator()
            .next();
        moved.x += 40;
        board.touchLayout();
        session.save(p);
        tickUntil("applied", () -> opsFrames() == 1 && !doc.waiting());
        tickUntil("read back", () -> slot(p, ME + ":d1") != board);
        assertEquals(com.gtnhplanner.library.PlanExport.gameX(777), slot(p, ME + ":d1").nodes.get(moved.id).x);
    }

    @Test
    void bigEditsGoInBatchesUnderTheLimitsAndAHugeCardStaysOut() {
        inAccount("d1", "sulfuric-acid");
        final Plan p = begin();
        final Graph board = open(p, ME + ":d1");
        final LiveDoc doc = session.doc(board);
        for (int i = 0; i < 2200; i++) {
            final com.gtnhplanner.data.flowchart.Note n = new com.gtnhplanner.data.flowchart.Note();
            n.getText()
                .add("note " + i);
            board.notes.put(n.getId(), n);
        }
        final com.gtnhplanner.data.flowchart.Note huge = new com.gtnhplanner.data.flowchart.Note();
        huge.getText()
            .add("h".repeat(300_000));
        board.notes.put(huge.getId(), huge);
        board.touchLayout();
        session.save(p);
        tickUntil("all sent", () -> opsFrames() >= 3 && !doc.waiting());
        int ops = 0;
        for (final String frame : live.received) {
            if (!frame.contains("\"t\":\"ops\"")) continue;
            assertTrue(frame.getBytes(StandardCharsets.UTF_8).length <= 256 * 1024);
            final int n = new JsonParser().parse(frame)
                .getAsJsonObject()
                .getAsJsonArray("ops")
                .size();
            assertTrue(n <= 1000);
            ops += n;
        }
        assertEquals(2200, ops, "the huge card was not sent");
        assertFalse(host.notices.isEmpty(), "and the player was told");
    }

}
