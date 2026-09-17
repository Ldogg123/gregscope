package io.github.ldogg123.gregscope.gametest;

import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Node;

/** Minimal machine context for invoking component callbacks directly from a game test. */
final class StubContext implements Context {

    @Override
    public Node node() {
        return null;
    }

    @Override
    public boolean canInteract(String player) {
        return true;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public boolean pause(double seconds) {
        return false;
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public void consumeCallBudget(double callCost) {}

    @Override
    public boolean signal(String name, Object... args) {
        return false;
    }
}
