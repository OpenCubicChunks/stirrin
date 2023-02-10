package io.github.opencubicchunks.stirrin.resolution;

import io.github.opencubicchunks.stirrin.ty.SpecifiedType;

import java.io.File;
import java.util.Set;

public class Resolver {
    private final String classPackage;
    private final Set<File> sourceSetDirectories;
    private final Set<String> imports;

    public Resolver(String classPackage, Set<File> sourceSetDirectories, Set<String> imports) {
        this.classPackage = classPackage;
        this.sourceSetDirectories = sourceSetDirectories;
        this.imports = imports;
    }

    public SpecifiedType resolveClass(String classToResolve) {
        return ResolutionUtils.resolveClass(this.classPackage, classToResolve, this.imports, this.sourceSetDirectories);
    }

    public SpecifiedType resolveClassWithTypeParameters(String classToResolve, Set<String> typeParameters) {
        return ResolutionUtils.resolveClassWithTypeParameters(this.classPackage, classToResolve, this.imports, this.sourceSetDirectories, typeParameters);
    }
}
