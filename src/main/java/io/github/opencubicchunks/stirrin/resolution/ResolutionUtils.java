package io.github.opencubicchunks.stirrin.resolution;

import io.github.opencubicchunks.stirrin.ty.SpecifiedType;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class ResolutionUtils {
    private static final Set<String> JAVA_LANG_IMPORTS = new HashSet<>();
    private static final Set<String> FAILED_JAVA_LANG_IMPORTS = new HashSet<>();

    private static void addLangImportIfRequired(String className) {
        String javaLangClass = "java.lang." + className;
        if (JAVA_LANG_IMPORTS.contains(javaLangClass) || FAILED_JAVA_LANG_IMPORTS.contains(javaLangClass)) {
            return;
        }

        try (InputStream resource = ResolutionUtils.class.getResourceAsStream("/" + javaLangClass.replace('.', '/') + ".class")) {
            if (resource != null) {
                JAVA_LANG_IMPORTS.add(javaLangClass);
            } else {
                FAILED_JAVA_LANG_IMPORTS.add(javaLangClass);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static SpecifiedType resolveClassWithTypeParameters(String classPackage, String classToResolve, Collection<String> imports, Collection<File> sourceSets, Collection<String> typeParameters) {
        if (typeParameters.contains(classToResolve)) {
            return new SpecifiedType(classToResolve, SpecifiedType.TYPE.GENERIC);
        }
        return resolveClass(classPackage, classToResolve, imports, sourceSets);
    }

    public static SpecifiedType resolveClass(String classPackage, String classToResolve, Collection<String> imports, Collection<File> sourceSets) {
        imports = new HashSet<>(imports);

        classToResolve = classToResolve.replace('.', '$');
        int classDot = classToResolve.indexOf("$");
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
            return new SpecifiedType(resolvedClass, SpecifiedType.TYPE.CLASS);

        // Package imports
        resolvedClass = tryResolveFromSourceSet(classPackage, classToResolve, sourceSets);
        if (resolvedClass != null) {
            return new SpecifiedType(resolvedClass, SpecifiedType.TYPE.CLASS);
        }

        // Default imports
        addLangImportIfRequired(classToResolve);
        resolvedClass = tryResolveFromImports(classToResolve, JAVA_LANG_IMPORTS, sourceSets, outerClass, innerClass);
        if (resolvedClass != null)
            return new SpecifiedType(resolvedClass, SpecifiedType.TYPE.CLASS);

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

    @Nullable
    public static File fileFromNameAndSources(String descriptor, Collection<File> sourceSets) {
        String signature = descriptor.substring(1, descriptor.length() - 1);
        String path = signature.replace('/', File.separatorChar);
        for (File srcDir : sourceSets) {
            File resolvedFile = srcDir.toPath().resolve(path + ".java").toFile();
            if (resolvedFile.exists() && resolvedFile.isFile()) {
                return resolvedFile;
            }
        }

        // Maybe this was an inner class? Look for a file for the above class (if there is one)
        return tryResolveOuterClassFile(signature, sourceSets);
    }

    @Nullable
    private static File tryResolveOuterClassFile(String fullyQualifiedName, Collection<File> sourceSets) {
        int endIndex = fullyQualifiedName.indexOf('$');
        if (endIndex < 0) {
            return null;
        }
        String outerClassPath = fullyQualifiedName.substring(0, endIndex)
                .replace('.', File.separatorChar);
        for (File srcDir : sourceSets) {
            File resolvedFile = srcDir.toPath().resolve(outerClassPath + ".java").toFile();
            if (resolvedFile.exists() && resolvedFile.isFile()) {
                return resolvedFile;
            }
        }
        return null;
    }
}
