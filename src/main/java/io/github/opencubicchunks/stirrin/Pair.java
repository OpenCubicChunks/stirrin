package io.github.opencubicchunks.stirrin;

import java.util.Objects;

public final class Pair<L, R> {
    private final L l;
    private final R r;

    public Pair(L l, R r) {
        this.l = l;
        this.r = r;
    }

    public L l() {
        return l;
    }

    public R r() {
        return r;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        Pair that = (Pair) obj;
        return Objects.equals(this.l, that.l) &&
                Objects.equals(this.r, that.r);
    }

    @Override
    public int hashCode() {
        return Objects.hash(l, r);
    }

    @Override
    public String toString() {
        return "Pair[" +
                "l=" + l + ", " +
                "r=" + r + ']';
    }
}
