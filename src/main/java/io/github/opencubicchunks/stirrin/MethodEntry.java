package io.github.opencubicchunks.stirrin;

import java.util.List;
import java.util.Set;

public class MethodEntry {
    public final String name;
    public final List<Parameter> parameters;
    public final String returnType;
    public final Set<String> typeParameters;

    MethodEntry(String name, List<Parameter> parameters, String returnType, Set<String> typeParameters) {
        this.name = name;
        this.parameters = parameters;
        this.returnType = returnType;
        this.typeParameters = typeParameters;
    }

    public static class Parameter {
        public final String descriptor;

        private Parameter(String descriptor) {
            this.descriptor = descriptor;
        }

        public static Parameter fromPrimitive(String primitive) {
            return new Parameter(StirrinTransformer.signatureToDescriptor(primitive));
        }

        public static Parameter fromType(String fullyQualifiedName) {
            return new Parameter(StirrinTransformer.signatureToDescriptor(fullyQualifiedName));
        }
    }
}
