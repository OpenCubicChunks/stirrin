package io.github.opencubicchunks.stirrin;

public class DescriptorUtils {
    public static String classToDescriptor(String sig) {
        return "L" + sig.replace('.', '/') + ";";
    }
}
