package io.github.opencubicchunks.stirrin.ty;

import java.util.Collection;
import java.util.Objects;

public class MethodEntry {
    public final String name;
    public final String descriptor;
    public final String signature;
    public final Collection<String> parameterNames;
    public final Collection<String> exceptions;

    public MethodEntry(String name, String descriptor, String signature, Collection<String> parameterNames, Collection<String> exceptions) {
        this.name = name;
        this.descriptor = descriptor;
        this.signature = signature;
        this.parameterNames = parameterNames;
        this.exceptions = exceptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodEntry that = (MethodEntry) o;
        return Objects.equals(name, that.name) && Objects.equals(descriptor, that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, descriptor);
    }
}
