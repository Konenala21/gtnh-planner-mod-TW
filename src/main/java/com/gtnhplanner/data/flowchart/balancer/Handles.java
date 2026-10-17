package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.List;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Variable;

/**
 * A fresh ojAlgo model with its variable arrays unwrapped. Models are the ONE per-stage object in
 * the pipeline; the data that feeds them (machines/ports/gates) lives once on {@link ModelData}
 * and only these variable handles are new for each solve.
 *
 * @param extentVars   per machine
 * @param flowVars     per drawn edge
 * @param extVars      per connected port
 * @param conservation per connected port, its conservation row (empty in a model without them)
 * @param fed          extra crossings at a port ({@link Solver#feedPort}): the independent check counts each as that
 *                     port's external
 */
public record Handles(ExpressionsBasedModel model, Variable[] extentVars, Variable[] flowVars, Variable[] extVars,
    Expression[] conservation, List<Fed> fed) {

    /** A free crossing at connected port {@code port}: supply at an input, a sink at an output. */
    public record Fed(Variable variable, int port) {}

    public Handles(final ExpressionsBasedModel model, final Variable[] extentVars, final Variable[] flowVars,
        final Variable[] extVars, final Expression[] conservation) {
        this(model, extentVars, flowVars, extVars, conservation, new ArrayList<>());
    }
}
