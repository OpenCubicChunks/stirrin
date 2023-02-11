package io.github.opencubicchunks.stirrin.ty;

import io.github.opencubicchunks.stirrin.util.Pair;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

public class MethodEntry {
    public final String name;
    public final List<Pair<String, SpecifiedType>> parameters;
    public final SpecifiedType returnType;
    public final Set<String> typeParameters;
    @Nullable public final String methodSignature;
    public final List<String> exceptions;

    public MethodEntry(String name, List<Pair<String, SpecifiedType>> nameParameterPairs, SpecifiedType returnType, Set<String> typeParameters, @Nullable String methodSignature, List<String> exceptions) {
        this.name = name;
        this.parameters = nameParameterPairs;
        this.returnType = returnType;
        this.typeParameters = typeParameters;
        this.methodSignature = methodSignature;
        this.exceptions = exceptions;
    }
}
