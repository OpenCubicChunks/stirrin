package io.github.opencubicchunks.stirrin.resolution;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ResolutionUtils {
    private static final Set<String> JAVA_LANG_IMPORTS;

    static {
        HashSet<String> imports = new HashSet<>();
        try (ScanResult scanResult = new ClassGraph()
                .enableSystemJarsAndModules()
                .enableClassInfo()
                .acceptPackagesNonRecursive("java.lang")
                .scan()) {
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                if (!classInfo.isPublic() || classInfo.getOuterClasses().size() > 0)
                    continue;
                imports.add(classInfo.getName());
            }
        }
        JAVA_LANG_IMPORTS = Collections.unmodifiableSet(imports);
    }

    public static String resolveClassWithTypeParameters(String classPackage, String classToResolve, Collection<String> imports, Collection<File> sourceSets, Collection<String> typeParameters) {
        if (typeParameters.contains(classToResolve)) {
            return classToResolve;
        }
        return resolveClass(classPackage, classToResolve, imports, sourceSets);
    }

    public static String resolveClass(String classPackage, String classToResolve, Collection<String> imports, Collection<File> sourceSets) {
        imports = new HashSet<>(imports);

        int classDot = classToResolve.indexOf(".");
        String outerClass;
        String innerClass;
        if (classDot == -1) {
            outerClass = classToResolve;
            innerClass = "";
        } else {
            outerClass = classToResolve.substring(0, classDot);
            innerClass = classToResolve.substring(classDot);
        }

        String resolvedClass;
        // Class file imports
        resolvedClass = tryResolveFromImports(classToResolve, imports, sourceSets, outerClass, innerClass);
        if (resolvedClass != null)
            return resolvedClass;

        // Default imports
        resolvedClass = tryResolveFromImports(classToResolve, JAVA_LANG_IMPORTS, sourceSets, outerClass, innerClass);
        if (resolvedClass != null)
            return resolvedClass;

        // Package imports
        resolvedClass = tryResolveFromSourceSet(classPackage, classToResolve, sourceSets);
        if (resolvedClass != null) {
            return resolvedClass;
        }

        return null;
    }

    private static String tryResolveFromImports(String classToResolve, Collection<String> imports, Collection<File> sourceSets, String outerClass, String innerClass) {
        for (String anImport : imports) {
            if (anImport.endsWith("*")) {
                // Star imports
                String cl = tryResolveFromStarImport(anImport, classToResolve, sourceSets);
                if (cl != null) {
                    return cl;
                }
            }
            int dot = anImport.lastIndexOf('.');
            String simpleName = anImport.substring(dot + 1);

            if (outerClass.equals(simpleName)) {
                return anImport + innerClass;
            }
        }
        return null;
    }

    private static String tryResolveFromSourceSet(String importPkg, String classToResolve, Collection<File> sourceSets) {
        String packagePath = importPkg.replace('.', File.separatorChar);
        for (File srcDir : sourceSets) {
            if (Files.exists(srcDir.toPath().resolve(packagePath).resolve(classToResolve + ".java"))) {
                return importPkg + '.' + classToResolve;
            }
        }
        return null;
    }
    private static String tryResolveFromStarImport(String clazz, String classToResolve, Collection<File> sourceSets) {
        int dot = clazz.lastIndexOf('.');
        String importPkg = clazz.substring(0, dot);
        return tryResolveFromSourceSet(importPkg, classToResolve, sourceSets);
    }

    public static File fileFromNameAndSources(String fullyQualifiedName, Collection<File> sourceSets) {
        String path = fullyQualifiedName.replace('.', File.separatorChar);
        for (File srcDir : sourceSets) {
            Path resolvedPath = srcDir.toPath().resolve(path + ".java");
            if (Files.exists(resolvedPath)) {
                return resolvedPath.toFile();
            }
        }
        return null;
    }
}
