package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * The Up to rule answers the same in the mod as on the website (docs/design/up-to.md): every plan the website's
 * {@code parity/up-to-export.test.ts} wrote under {@code solver-corpus-upto/} (its hand-built Up to plans, and every
 * corpus plan with each exact count turned Up to) is solved here and its whole answer, the read-out and what a buffer
 * banks included, compared with the website's by the golden rules ({@link SolverCorpus#differences}: floating-point
 * noise and nothing else). Re-export there after a change to either engine.
 */
class UpToParityTest {

    private static final Path PLANS = Path.of("src/test/resources/solver-corpus-upto");

    /**
     * Plans the two engines answer alike only to within the LP's own tolerance, with why. In each the website's
     * committed point breaks one of its own rows by less than its solver's tolerance (its exact-basis refinement keeps
     * a basis whose primal breaks a row the basis does not hold tight), which ojAlgo, keeping the rows, does not: the
     * difference is a sliver near a millionth of a unit a second, too small to see, but it can open a gate or move a
     * few
     * millionths of a machine onto another recipe. For these the read-out and the machine counts are compared, the
     * counts to a hundred-thousandth of a machine; every other plan is compared whole.
     */
    private static final java.util.Map<String, String> WITHIN_TOLERANCE = java.util.Map.of(
        "mv-every-count-up-to.json",
        "the website's point loses 1.3e-6 charcoal/s at the pyrolyse oven's wired output (2.8e-7 of the port, inside"
            + " the conservation check's 1e-6); ojAlgo keeps it and sends it to the charcoal drawer, a gate",
        "idle-noise.json",
        "the website's canonical point runs 1.6e-7 over the sink drawers' held total and so routes 1e-6 machines"
            + " through other recipes than ojAlgo, which holds the total exactly",
        "idle-noise-beside-a-lone-card.json",
        "the same plan as idle-noise beside a lone Up to card");

    @BeforeAll
    static void profile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @TestFactory
    Stream<DynamicTest> theWebsiteAndTheModAnswerAlike() throws IOException {
        final List<Path> files;
        try (Stream<Path> listed = Files.list(PLANS)) {
            files = listed.filter(
                p -> p.getFileName()
                    .toString()
                    .endsWith(".json"))
                .sorted()
                .toList();
        }
        assertFalse(files.isEmpty(), "no Up to plans under " + PLANS);
        return files.stream()
            .map(
                file -> DynamicTest.dynamicTest(
                    file.getFileName()
                        .toString(),
                    () -> check(file)));
    }

    private static void check(final Path file) throws IOException {
        final JsonObject plan = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8))
            .getAsJsonObject();
        final Balancer.Answer answer = Balancer
            .solveWithAlternatives(SolveInputJson.read(plan.getAsJsonObject("input")));
        final JsonElement expected = plan.get("answer"), actual = normalised(digest(answer));
        final String why = WITHIN_TOLERANCE.get(
            file.getFileName()
                .toString());
        if (why != null) {
            final List<String> whole = SolverCorpus.differences(expected, actual);
            System.out.println(
                "[up to parity] " + file
                    .getFileName() + " within tolerance (" + why + "): " + whole.size() + " differences");
            final List<String> d = SolverCorpus.differences(part(expected, "upTo"), part(actual, "upTo"));
            d.addAll(countsDiffer(part(expected, "machineCounts"), part(actual, "machineCounts")));
            assertTrue(d.isEmpty(), file.getFileName() + " read-outs or counts differ:\n  " + String.join("\n  ", d));
            return;
        }
        final List<String> d = SolverCorpus.differences(expected, actual);
        assertTrue(
            d.isEmpty(),
            plan.get("name")
                .getAsString() + " answers differently from the website:\n  "
                + String.join("\n  ", d));
    }

    /** The solution's {@code key} part of a digest. */
    private static JsonElement part(final JsonElement digest, final String key) {
        final JsonObject solution = digest.getAsJsonObject()
            .getAsJsonObject("solution");
        return solution == null || !solution.has(key) ? new JsonObject() : solution.get(key);
    }

    /** Machine counts further apart than a hundred-thousandth of a machine. */
    private static List<String> countsDiffer(final JsonElement expected, final JsonElement actual) {
        final List<String> out = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<String, JsonElement> e : expected.getAsJsonObject()
            .entrySet()) {
            final JsonElement got = actual.getAsJsonObject()
                .get(e.getKey());
            if (got == null || Math.abs(
                got.getAsDouble() - e.getValue()
                    .getAsDouble())
                > 1e-5) out.add("machineCounts/" + e.getKey() + ": " + e.getValue() + " / " + got);
        }
        return out;
    }

    /**
     * The digest as the website writes it: the shortfalls' limiting nodes and drawers are sets there, sorted by their
     * text ({@code digest.ts}); here they are lists in the order the relaxation found them.
     */
    static JsonElement normalised(final JsonElement digest) {
        if (digest.isJsonArray()) {
            for (final JsonElement e : digest.getAsJsonArray()) normalised(e);
        } else if (digest.isJsonObject()) {
            final JsonObject o = digest.getAsJsonObject();
            for (final String key : List.of("limitingNodes", "limitingDrawers")) {
                if (!o.has(key) || !o.get(key)
                    .isJsonArray()) continue;
                final List<JsonElement> items = new java.util.ArrayList<>();
                for (final JsonElement e : o.getAsJsonArray(key)) items.add(e);
                items.sort(
                    (a, b) -> a.toString()
                        .compareTo(b.toString()));
                final com.google.gson.JsonArray sorted = new com.google.gson.JsonArray();
                for (final JsonElement e : items) sorted.add(e);
                o.add(key, sorted);
            }
            for (final java.util.Map.Entry<String, JsonElement> e : o.entrySet()) normalised(e.getValue());
        }
        return digest;
    }

    /** The golden digest of a solved answer; a failed one as its failure, as the website writes it. */
    static JsonElement digest(final Balancer.Answer answer) {
        if (answer instanceof final Balancer.Answer.Failed failed) {
            final JsonObject j = new JsonObject();
            j.addProperty(
                "failed",
                failed.failure()
                    .message()
                    .name());
            return j;
        }
        return SolverCorpus.digest(answer);
    }
}
