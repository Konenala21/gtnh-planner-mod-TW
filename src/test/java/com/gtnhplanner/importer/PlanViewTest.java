package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * A shared plan opens in its author's rate unit (the website's plan-view.ts): the plan's {@code view.rateUnit} in the
 * website's words, written on the way out and taken on the way in.
 */
class PlanViewTest {

    private static JsonObject plan(final String view) {
        return new JsonParser().parse("{\"recipes\":[],\"nodes\":[]" + view + "}")
            .getAsJsonObject();
    }

    @Test
    void theWebsitesWordsForTheRateKey() {
        assertEquals("tick", PlanView.word(Fmt.RateUnit.TICK, false));
        assertEquals("second", PlanView.word(Fmt.RateUnit.SECOND, false));
        assertEquals("minute", PlanView.word(Fmt.RateUnit.MINUTE, false));
        assertEquals("hour", PlanView.word(Fmt.RateUnit.HOUR, false));
        assertEquals("eu", PlanView.word(Fmt.RateUnit.MINUTE, true), "EU whatever the clock");
        for (final Fmt.RateUnit u : Fmt.RateUnit.values()) assertEquals(u, PlanView.unit(PlanView.word(u, false)));
        assertNull(PlanView.unit("eu"));
    }

    @Test
    void aPlanSaysItsRateUnitOnlyInWordsItKnows() {
        assertEquals("hour", PlanView.rateUnit(plan(",\"view\":{\"rateUnit\":\"hour\",\"canvasPattern\":\"dots\"}")));
        assertEquals("eu", PlanView.rateUnit(plan(",\"view\":{\"rateUnit\":\"eu\"}")));
        assertNull(PlanView.rateUnit(plan("")), "no view: the player's own");
        assertNull(PlanView.rateUnit(plan(",\"view\":{\"canvasTheme\":\"x\"}")));
        assertNull(PlanView.rateUnit(plan(",\"view\":{\"rateUnit\":\"fortnight\"}")));
        assertNull(PlanView.rateUnit(plan(",\"view\":{\"rateUnit\":3}")));
        assertNull(PlanView.rateUnit(plan(",\"view\":\"minute\"")));
    }

    @Test
    void aPlanLinkCarriesItsRateUnitThereAndBack() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        final Graph g = FfImport.importFromText(FakeGame.fixture("high-amperage-reactor.json"), game, game)
            .graph();
        assertNull(g.takeView(), "the website's own fixture has no view");
        final PlanExport.World base = LibraryRoundTripTest.world(game, g);
        final JsonObject posted = PlanExport.project(g, g.getName(), new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                return base.port(port);
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return base.resource(key);
            }

            @Override
            public String machine(final Node node) {
                return base.machine(node);
            }

            @Override
            public double machines(final Node node) {
                return base.machines(node);
            }

            @Override
            public String tier(final Node node) {
                return base.tier(node);
            }

            @Override
            public String handlerId(final Node node) {
                return base.handlerId(node);
            }

            @Override
            public Web.Recipe gregtech(final Node node) {
                return base.gregtech(node);
            }

            @Override
            public JsonObject neiRecipe(final Node node) {
                return base.neiRecipe(node);
            }

            @Override
            public String rateUnit() {
                return "minute";
            }
        });
        assertEquals(
            "minute",
            posted.getAsJsonObject("view")
                .get("rateUnit")
                .getAsString());
        // As a plan link, the website's #p= code: opened, the board shows it per minute, once.
        final String link = "https://example.test/#p=" + FfPlanCode.encode(posted.toString());
        final Graph back = FfImport.importFromText(link, game, game)
            .graph();
        assertEquals("minute", back.takeView());
        assertNull(back.takeView(), "taken once: the player's own key from then on");
        assertTrue(back.nodes.size() > 0);
    }
}
