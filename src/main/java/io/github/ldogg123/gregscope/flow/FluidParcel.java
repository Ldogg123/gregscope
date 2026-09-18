package io.github.ldogg123.gregscope.flow;

/**
 * An amount of one fluid, as a {@code FluidStack} would carry it, with the fluid itself kept as an opaque token.
 * Immutable. [pure]
 *
 * <p>
 * The token is whatever the caller uses to tell two fluids apart: the adapter in the cover passes Forge's
 * {@code Fluid} instance, and a test passes a string. {@link FluidMover} only ever compares tokens by identity and
 * hands them back, so it never needs to know what a fluid is - which is what keeps the mover, and therefore the
 * counting rules, testable on a plain JVM.
 */
public final class FluidParcel {

    private final Object fluid;
    private final int amount;

    private FluidParcel(Object fluid, int amount) {
        this.fluid = fluid;
        this.amount = amount;
    }

    /**
     * A parcel, or {@code null} when there is nothing to carry - which is how Forge's {@code drain} reports an empty
     * tank, and what {@link FluidMover} has to treat the same way.
     *
     * @param fluid  the fluid token; {@code null} yields {@code null}, never a parcel of an unknown fluid
     * @param amount millibuckets; zero or less yields {@code null}
     */
    public static FluidParcel of(Object fluid, int amount) {
        if (fluid == null || amount <= 0) {
            return null;
        }
        return new FluidParcel(fluid, amount);
    }

    /** The same fluid in a different amount, or {@code null} when {@code amount} is not positive. */
    public FluidParcel withAmount(int newAmount) {
        return of(fluid, newAmount);
    }

    public Object fluid() {
        return fluid;
    }

    public int amount() {
        return amount;
    }

    @Override
    public String toString() {
        return amount + " of " + fluid;
    }
}
