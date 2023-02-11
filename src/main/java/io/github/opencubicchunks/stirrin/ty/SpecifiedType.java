package io.github.opencubicchunks.stirrin.ty;

import static io.github.opencubicchunks.stirrin.DescriptorUtils.signatureToDescriptor;

public class SpecifiedType {
    public final String fullyQualifiedName;
    public final String descriptor;
    public final TYPE type;

    public SpecifiedType(String fullyQualifiedName, TYPE type) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.descriptor = signatureToDescriptor(fullyQualifiedName, type);
        this.type = type;
    }

    public enum TYPE {
        PRIMITIVE,
        ARRAY,
        CLASS,
        GENERIC
    }
}
