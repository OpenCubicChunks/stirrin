package io.github.opencubicchunks.stirrin;

import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResolutionUtils {
    private static final Pattern IMPORTS_PATTERN = Pattern.compile("import(?: static)?\\s+((?:\\s*\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+\\s*\\.)*"
            + "(?:\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+|\\*));");
    private static final Pattern INTERFACES_PATTERN = Pattern.compile("class\\s+\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+\\s+(?:extends\\s+\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+\\s+)"
            + "?implements\\s+(?<implements>(?:(?:\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+\\s*,\\s*)*)(?:\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+))\\s*\\{");;
    public static final Pattern MIXIN_TARGET_PATTERN = Pattern.compile("@\\s*Mixin\\s*\\((?:\\s*value\\s*=)?\\s*(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}+\\s*\\.\\s*class)");

    /**
     * @param sourceSets Source sets to search for interfaces in the case of * imports
     * @param classText The class file source
     * @return a set of all interfaces implemented by a class. Returned interfaces have fully qualified names
     */
    public static Set<String> resolveInterfaces(List<String> imports, SourceSetContainer sourceSets, String classText) {
        Matcher interfacesMatcher = INTERFACES_PATTERN.matcher(classText);
        if (!interfacesMatcher.find()) {
            return Collections.emptySet();
        }
        String[] implementedClasses = interfacesMatcher.group("implements").split("\\s*,\\s*");
        Set<String> resolvedInterfaces = new HashSet<>();
        for (String implementedClass : implementedClasses) {
            resolvedInterfaces.add(resolveClass(implementedClass, imports, sourceSets));
        }
        return resolvedInterfaces;
    }

    public static List<String> resolveImports(String classText) {
        Matcher importsMatcher = IMPORTS_PATTERN.matcher(classText);

        List<String> imports = new ArrayList<>();
        while (importsMatcher.find()) {
            imports.add(importsMatcher.group(1));
        }
        return imports;
    }

    public static String resolveClass(String implementedClass, List<String> imports, SourceSetContainer sourceSets) {

        for (String anImport : imports) {
            if (anImport.endsWith("*")) {
                String cl = tryResolveFromStarImport(anImport, implementedClass, sourceSets);
                if (cl != null) {
                    return cl;
                }
            }
            int dot = anImport.lastIndexOf('.');
            String simpleName = anImport.substring(dot + 1);

            if (implementedClass.equals(simpleName)) {
                return anImport;
            }
        }
        throw new RuntimeException("Could not resolve class " + implementedClass);
    }

    private static String tryResolveFromStarImport(String importValue, String simpleName, SourceSetContainer sourceSets) {
        int dot = importValue.lastIndexOf('.');
        String importPkg = importValue.substring(0, dot);
        String packagePath = importPkg.replace('.', File.separatorChar);
        for (SourceSet sourceSet : sourceSets) {
            for (File srcDir : sourceSet.getJava().getSrcDirs()) {
                if (Files.exists(srcDir.toPath().resolve(packagePath).resolve(simpleName + ".java"))) {
                    return importPkg + '.' + simpleName;
                }
            }
        }
        return null;
    }
}
