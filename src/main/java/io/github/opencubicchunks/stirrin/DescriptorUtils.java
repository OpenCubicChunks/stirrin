package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.ty.SpecifiedType;

public class DescriptorUtils {
    public static String innerClassOf(String descriptor, String innerClass) {
        return descriptor.substring(0, descriptor.length() - 1) + innerClass + ";";
    }

    public static String classToDescriptor(String sig) {
        return "L" + sig.replace('.', '/') + ";";
    }

    public static char primitiveToDescriptor(String sig) {
        switch (sig) {
            case "void":
                return 'V';
            case "boolean":
                return 'Z';
            case "char":
                return 'C';
            case "byte":
                return 'B';
            case "short":
                return 'S';
            case "int":
                return 'I';
            case "float":
                return 'F';
            case "long":
                return 'J';
            case "double":
                return 'D';
            default:
                throw new IllegalArgumentException("Invalid primitive signature: " + sig);
        }
    }

    public static String signatureToDescriptor(String sig, SpecifiedType.TYPE type) {
        // Trivial primitive types
        switch (type) {
            case PRIMITIVE:
                return String.valueOf(primitiveToDescriptor(sig));
            case ARRAY:
                sig = sig.replace("void", "V");
                sig = sig.replace("boolean", "Z");
                sig = sig.replace("char", "C");
                sig = sig.replace("byte", "B");
                sig = sig.replace("short", "S");
                sig = sig.replace("int", "I");
                sig = sig.replace("float", "F");
                sig = sig.replace("long", "J");
                sig = sig.replace("double", "D");
                return sig;
            case CLASS:
                return "L" + sig.replace('.', '/') + ";";
            case GENERIC:
                return sig;
        }
        throw new IllegalArgumentException("Could not convert signature to descriptor " + sig, null);
    }
}
