package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.resolution.ResolutionUtils;
import io.github.opencubicchunks.stirrin.resolution.Resolver;
import org.eclipse.jdt.core.dom.*;
import org.gradle.api.GradleScriptException;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.opencubicchunks.stirrin.Stirrin.LOGGER;
import static org.eclipse.jdt.core.dom.AST.JLS18;

public abstract class StirrinTransform implements TransformAction<StirrinTransform.Parameters> {
    interface Parameters extends TransformParameters {
        @Input String getAcceptedJars();
        void setAcceptedJars(String acceptedJars);

        @InputFile Set<String> getConfigs();
        void setConfigs(Set<String> configs);

        @Input long getDebug();
        void setDebug(long l);

        @InputFile
        Set<File> getSourceSetDirectories();
        void setSourceSetDirectories(Set<File> sourceSetDirectories);

        @InputFile @org.gradle.api.tasks.Optional
        Set<File> getAdditionalSourceSets();
        void setAdditionalSourceSets(Set<File> sourceSetDirectories);

        @Input
        Map<File, String> getMixinFiles();
        void setMixinFiles(Map<File, String> mixinClassFiles);
    }

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        String fileName = getInputArtifact().get().getAsFile().getName();

        Pattern acceptedJars = Pattern.compile(getParameters().getAcceptedJars());

        Set<File> sourceSetDirectories = getParameters().getSourceSetDirectories();
        sourceSetDirectories.addAll(getParameters().getAdditionalSourceSets());

        if (acceptedJars.matcher(fileName).matches()) {
            LOGGER.warn(String.format("found %s", getInputArtifact().get().getAsFile()));

            String fileNameNoExt = fileName.substring(0, fileName.lastIndexOf("."));
            String outputFileName = fileNameNoExt + "-mixinInterfaces.jar";

            ASTParser astParser = createASTParser(new HashSet<>(), new HashSet<>());

            Map<String, Set<String>> mixinInterfacesByTarget = getMixinInterfacesByTarget(getParameters().getMixinFiles(), astParser, sourceSetDirectories);
            LOGGER.debug("Mixin interfaces: " + mixinInterfacesByTarget);

            Set<String> mixinInterfaces = new HashSet<>();
            mixinInterfacesByTarget.forEach((target, interfaces) -> {
                mixinInterfaces.addAll(interfaces);
            });

            Map<File, String> mixinInterfaceFiles = new HashMap<>();
            for (String mixinInterface : mixinInterfaces) {
                File file = ResolutionUtils.fileFromNameAndSources(mixinInterface, sourceSetDirectories);
                if (file == null) {
                    LOGGER.error("Failed to find file for mixin interface: " + mixinInterface + ". Ignoring.");
                    continue;
                }
                mixinInterfaceFiles.put(file, mixinInterface);
            }
            Map<String, List<MethodEntry>> mixinInterfaceMethods = getMixinInterfaceMethods(mixinInterfaceFiles, astParser, sourceSetDirectories);

            StirrinTransformer.transformMinecraftJar(mixinInterfacesByTarget, mixinInterfaceMethods, getInputArtifact().get().getAsFile(), outputs.file(outputFileName));

            LOGGER.warn(String.format("transformed %s", outputFileName));
        } else {
            LOGGER.info(String.format("Rejected jar %s", fileName));
            outputs.file(getInputArtifact());
        }
    }

    private Map<String, List<MethodEntry>> getMixinInterfaceMethods(Map<File, String> mixinInterfaceFiles, ASTParser parser, Set<File> sourceSetDirectories) {
        Map<String, List<MethodEntry>> methodsByInterface = new HashMap<>();

        for (Map.Entry<File, String> mixinPair : mixinInterfaceFiles.entrySet()) {
            try {
                String classSource = Files.readString(mixinPair.getKey().toPath());
                parser.setSource(classSource.toCharArray());

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);
                String classPackage = String.valueOf(cu.getPackage().getName());
                Set<String> imports = getImportsFrom(cu);

                Resolver resolver = new Resolver(classPackage, sourceSetDirectories, imports);

                List<MethodEntry> methods = new ArrayList<>();
                // TODO: make sure this properly handles inner classes
                for (Object type : cu.types()) {
                    if (type instanceof TypeDeclaration) {
                        TypeDeclaration typeDecl = (TypeDeclaration) type;

                        getMethodEntries(typeDecl, methods, mixinPair, resolver);
                    }
                }
                methodsByInterface.put(mixinPair.getValue(), methods);
            } catch (Throwable e) {
                e.printStackTrace();
                throw new GradleScriptException("", e);
            }
        }
        return methodsByInterface;
    }

    private static void getMethodEntries(TypeDeclaration typeDecl, List<MethodEntry> methods, Map.Entry<File, String> mixinPair, Resolver resolver) {
        String name = mixinPair.getValue();
        String[] split = name.split("\\.");
        if (!String.valueOf(typeDecl.getName()).equals(split[split.length-1])) {
            LOGGER.warn("Skipping unspecified class " + typeDecl.getName());
            return;
        }

        for (MethodDeclaration method : typeDecl.getMethods()) {
            if (!methodIsInterfaceMethod(method))
                continue;

            String methodName = String.valueOf(method.getName());

            Set<String> typeParameters = new HashSet<>();
            List<?> list = method.typeParameters();
            for (Object typeParameter : list) {
                if (typeParameter instanceof TypeParameter) {
                    TypeParameter typeParam = (TypeParameter) typeParameter;
                    typeParameters.add(String.valueOf(typeParam.getName()));
                }
            }

            String returnType = getMethodReturnType(method, resolver, typeParameters);
            // TODO: remove null check
            if (returnType == null) {
                LOGGER.warn("Failed to parse method return type for: " + methodName);
                continue;
            }

            List<MethodEntry.Parameter> parameters = getMethodParameters(method, resolver, typeParameters);

            methods.add(new MethodEntry(methodName, parameters, returnType, typeParameters));
        }
    }

    private static String getMethodReturnType(MethodDeclaration method, Resolver resolver, Set<String> typeParameters) {
        Type returnTy = method.getReturnType2();
        if (returnTy == null) {
            return null;
        }
        return getFullyQualifiedTypeName(returnTy, resolver, typeParameters);
    }

    private static List<MethodEntry.Parameter> getMethodParameters(MethodDeclaration method, Resolver resolver, Set<String> typeParameters) {
        List<MethodEntry.Parameter> parameters = new ArrayList<>();

        for (Object parameter : method.parameters()) {
            if (parameter instanceof SingleVariableDeclaration) {
                SingleVariableDeclaration param = (SingleVariableDeclaration) parameter;
                Type paramType = param.getType();
                String fullyQualifiedType = getFullyQualifiedTypeName(paramType, resolver, typeParameters);
                // TODO: remove null check
                if (fullyQualifiedType == null) {
                    LOGGER.warn("Failed to parse method parameter type for: " + paramType);
                    continue;
                }

                if (fullyQualifiedType.contains(".")) {
                    parameters.add(MethodEntry.Parameter.fromType(fullyQualifiedType));
                } else {
                    parameters.add(MethodEntry.Parameter.fromPrimitive(fullyQualifiedType));
                }
            } else {
                throw new GradleScriptException("Unhandled method parameter class", null);
            }
        }
        return parameters;
    }

    // TODO: figure out how to do this more reliably. This doesn't take into account inner interfaces, or package private methods in an inner class.
    //       This is very bad.
    private static boolean methodIsInterfaceMethod(MethodDeclaration method) {
        List<?> modifiers = method.modifiers();
        for (Object modifier : modifiers) {
            if (modifier instanceof Modifier) {
                Modifier mod = (Modifier) modifier;
                if (mod.isPublic() || mod.isProtected() || mod.isPrivate() || mod.isAbstract() || mod.isNative()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String getFullyQualifiedTypeName(Type paramType, Resolver resolver, Set<String> typeParameters) {
        String fullyQualifiedType = null;
        if (paramType.isSimpleType()) {
            SimpleType simpleType = (SimpleType) paramType;
            fullyQualifiedType = resolver.resolveClassWithTypeParameters(String.valueOf(simpleType.getName()), typeParameters);
        } else if (paramType.isPrimitiveType()) {
            fullyQualifiedType = paramType.toString();
        } else if (paramType.isParameterizedType()) {
            ParameterizedType type = (ParameterizedType) paramType;
            fullyQualifiedType = getFullyQualifiedTypeName(type.getType(), resolver, typeParameters);
        } else {
            LOGGER.error("Unhandled parameter type");
        }
        return fullyQualifiedType;
    }

    private static ASTParser createASTParser(Set<File> dependencyClasses, Set<File> projectClasses) {
        ASTParser parser = ASTParser.newParser(JLS18);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);

        dependencyClasses = dependencyClasses == null ? new HashSet<>() : dependencyClasses;
        projectClasses = projectClasses == null ? new HashSet<>() : projectClasses;

        parser.setEnvironment(
                dependencyClasses.stream().map(file -> file.toPath().toAbsolutePath().toString()).collect(Collectors.toList()).toArray(new String[0]),
                projectClasses.stream().map(file -> file.toPath().toAbsolutePath().toString()).collect(Collectors.toList()).toArray(new String[0]),
                null, false);
        return parser;
    }

    private static Map<String, Set<String>> getMixinInterfacesByTarget(Map<File, String> mixinClassFiles, ASTParser parser, Set<File> sourceSetDirectories) {
        Map<String, Set<String>> interfacesByTarget = new HashMap<>();
        for (Map.Entry<File, String> mixinPair : mixinClassFiles.entrySet()) {
            try {
                String classSource = Files.readString(mixinPair.getKey().toPath());
                parser.setSource(classSource.toCharArray());

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);
                String classPackage = String.valueOf(cu.getPackage().getName());
                Set<String> imports = getImportsFrom(cu);

                Resolver resolver = new Resolver(classPackage, sourceSetDirectories, imports);

                for (Object type : cu.types()) {
                    if (type instanceof TypeDeclaration) {
                        TypeDeclaration typeDecl = (TypeDeclaration) type;

                        Set<String> mixinTargets = new HashSet<>();
                        for (Object modifier : typeDecl.modifiers()) {
                            if (modifier instanceof SingleMemberAnnotation) {
                                SingleMemberAnnotation annotation = (SingleMemberAnnotation) modifier;
                                String typeName = String.valueOf(annotation.getTypeName());
                                String annotationClassName = resolver.resolveClass(typeName);
                                if (annotationClassName.equals("org.spongepowered.asm.mixin.Mixin")) {
                                    Expression value = annotation.getValue();
                                    if (value instanceof TypeLiteral) {
                                        mixinTargets.add(getName((TypeLiteral) value, resolver).orElseThrow());
                                        break;
                                    } else if (value instanceof ArrayInitializer) {
                                        mixinTargets.addAll(getNames((ArrayInitializer) value, resolver));
                                        break;
                                    } else {
                                        throw new GradleScriptException("Unhandled mixin annotation expression", null);
                                    }
                                }
                            }
                        }

                        if (!mixinTargets.isEmpty()) {
                            Set<String> interfaces = new HashSet<>();
                            List<?> interfaceTypes = typeDecl.superInterfaceTypes();
                            for (Object anInterface : interfaceTypes) {
                                if (anInterface instanceof SimpleType) {
                                    SimpleType itf = (SimpleType) anInterface;
                                    interfaces.add(ResolutionUtils.resolveClass(classPackage, String.valueOf(itf.getName()), imports, sourceSetDirectories));
                                }
                            }

                            for (String mixinTarget : mixinTargets) {
                                interfacesByTarget.put(mixinTarget, interfaces);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                throw new GradleScriptException("", e);
            }
        }
        return interfacesByTarget;
    }

    private static Set<String> getImportsFrom(CompilationUnit cu) {
        Set<String> imports = new HashSet<>();
        for (Object anImport : cu.imports()) {
            if (anImport instanceof ImportDeclaration) {
                ImportDeclaration imp = (ImportDeclaration) anImport;
                imports.add(String.valueOf(imp.getName().getFullyQualifiedName()));
            }
        }
        return imports;
    }

    private static Set<String> getNames(ArrayInitializer array, Resolver resolver) {
        Set<String> names = new HashSet<>();
        for (Object expression : array.expressions()) {
            if (expression instanceof TypeLiteral) {
                TypeLiteral literal = (TypeLiteral) expression;
                names.add(getName(literal, resolver).orElseThrow());
            }
        }
        return names;
    }

    private static Optional<String> getName(TypeLiteral literal, Resolver resolver) {
        if (literal.getType().isSimpleType()) {

            String name = String.valueOf(((SimpleType) literal.getType()).getName());
            return Optional.ofNullable(resolver.resolveClass(name));
        }
        return Optional.empty();
    }
}