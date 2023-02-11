package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.resolution.ResolutionUtils;
import io.github.opencubicchunks.stirrin.resolution.Resolver;
import io.github.opencubicchunks.stirrin.ty.MethodEntry;
import io.github.opencubicchunks.stirrin.ty.SpecifiedType;
import org.eclipse.jdt.core.dom.*;
import org.gradle.api.GradleScriptException;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.objectweb.asm.signature.SignatureWriter;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

import static io.github.opencubicchunks.stirrin.DescriptorUtils.classToDescriptor;
import static io.github.opencubicchunks.stirrin.DescriptorUtils.innerClassOf;
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
        Set<File> additionalSourceSets = getParameters().getAdditionalSourceSets();
        if (additionalSourceSets != null)
            sourceSetDirectories.addAll(additionalSourceSets);

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

            Map<File, Set<String>> mixinInterfaceFiles = new HashMap<>();
            for (String mixinInterfaceDescriptor : mixinInterfaces) {
                File file = ResolutionUtils.fileFromNameAndSources(mixinInterfaceDescriptor, sourceSetDirectories);
                if (file == null) {
                    LOGGER.error("Failed to find file for mixin interface: " + mixinInterfaceDescriptor + ". Ignoring.");
                    continue;
                }
                mixinInterfaceFiles.computeIfAbsent(file, f -> new HashSet<>()).add(mixinInterfaceDescriptor);
            }
            Map<String, List<MethodEntry>> mixinInterfaceMethods = getMixinInterfaceMethods(mixinInterfaceFiles, astParser, sourceSetDirectories);

            StirrinTransformer.transformMinecraftJar(mixinInterfacesByTarget, mixinInterfaceMethods, getInputArtifact().get().getAsFile(), outputs.file(outputFileName));

            LOGGER.warn(String.format("transformed %s", outputFileName));
        } else {
            LOGGER.info(String.format("Rejected jar %s", fileName));
            outputs.file(getInputArtifact());
        }
    }

    /**
     *
     * @param mixinInterfaceFiles A map from Mixin interface files to their fully qualified class name
     * @param parser The ASTParser to use
     * @param sourceSetDirectories The source sets to use for class resolution
     * @return A map from interfaces to their methods
     */
    private Map<String, List<MethodEntry>> getMixinInterfaceMethods(Map<File, Set<String>> mixinInterfaceFiles, ASTParser parser, Set<File> sourceSetDirectories) {
        Map<String, List<MethodEntry>> methodsByInterface = new HashMap<>();

        for (Map.Entry<File, Set<String>> mixinPair : mixinInterfaceFiles.entrySet()) {
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
                        addMethodEntriesForType(mixinPair.getValue(), classToDescriptor(classPackage + "." + typeDecl.getName()), resolver, methodsByInterface, typeDecl);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                throw new GradleScriptException("", e);
            }
        }
        return methodsByInterface;
    }

    private static void addMethodEntriesForType(Set<String> mixinInterfaces, String classDescriptor, Resolver resolver, Map<String, List<MethodEntry>> methodsByInterface, TypeDeclaration typeDecl) {
        if (mixinInterfaces.contains(classDescriptor)) {
            methodsByInterface.computeIfAbsent(classDescriptor, n -> new ArrayList<>())
                    .addAll(getMethodEntries(typeDecl, resolver));
        } else {
            LOGGER.warn("Skipping non-targeted class " + typeDecl.getName());
        }

        for (TypeDeclaration innerType : typeDecl.getTypes()) {
            String innerClassDescriptor = innerClassOf(classDescriptor, String.valueOf(innerType.getName()));
            if (mixinInterfaces.contains(innerClassDescriptor)) {
                addMethodEntriesForType(mixinInterfaces, innerClassDescriptor, resolver, methodsByInterface, innerType);
            } else {
                LOGGER.warn("Skipping non-targeted class " + innerClassDescriptor);
            }
        }
    }

    /**
     * Finds all methods within a type, and creates a {@link MethodEntry} for each
     *
     * @param typeDecl The {@link TypeDeclaration} to search methods in
     * @param resolver The resolver to use.
     * @return The list of method entries for the supplied {@link TypeDeclaration}
     */
    private static List<MethodEntry> getMethodEntries(TypeDeclaration typeDecl, Resolver resolver) {
        List<MethodEntry> methods = new ArrayList<>();
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

            SpecifiedType returnType = getMethodReturnType(method, resolver, typeParameters);
            // TODO: remove null check
            if (returnType == null) {
                LOGGER.error("Failed to parse method return type for: " + methodName);
                continue;
            }

            List<SpecifiedType> parameters = getMethodParameters(method, resolver, typeParameters);

            String methodSignature = createMethodSignature(method, resolver, typeParameters);

            methods.add(new MethodEntry(methodName, parameters, returnType, typeParameters, methodSignature));
        }
        return methods;
    }

    private static SpecifiedType getMethodReturnType(MethodDeclaration method, Resolver resolver, Set<String> typeParameters) {
        Type returnTy = method.getReturnType2();
        if (returnTy == null) {
            return null;
        }
        return getFullyQualifiedTypeName(returnTy, resolver, typeParameters);
    }

    private static List<SpecifiedType> getMethodParameters(MethodDeclaration method, Resolver resolver, Set<String> typeParameters) {
        List<SpecifiedType> parameters = new ArrayList<>();

        for (Object parameter : method.parameters()) {
            if (parameter instanceof SingleVariableDeclaration) {
                SingleVariableDeclaration param = (SingleVariableDeclaration) parameter;
                Type paramType = param.getType();
                SpecifiedType fullyQualifiedType = getFullyQualifiedTypeName(paramType, resolver, typeParameters);
                // TODO: remove null check
                if (fullyQualifiedType == null) {
                    fullyQualifiedType = new SpecifiedType("java.lang.Object", SpecifiedType.TYPE.CLASS);
                    LOGGER.error("Failed to parse method parameter type for: " + paramType + " replacing it with Object");
                }

                parameters.add(fullyQualifiedType);
            } else {
                throw new RuntimeException("Unhandled method parameter class");
            }
        }
        return parameters;
    }

    // TODO: figure out how to do this more reliably. This doesn't take into account inner interfaces, or package private methods in an inner class.
    //       This is very bad.
    //       Enums seem to be represented differently in the AST causing this issue
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

    /**
     * Attempts to use the resolver and type parameters to resolve a type name
     * @param paramType The type to resolve
     * @param resolver The resolver
     * @param typeParameters Any type parameters
     * @return The fully qualified class name
     */
    private static SpecifiedType getFullyQualifiedTypeName(Type paramType, Resolver resolver, Set<String> typeParameters) {
        if (paramType.isSimpleType()) {
            SimpleType simpleType = (SimpleType) paramType;
            return resolver.resolveClassWithTypeParameters(String.valueOf(simpleType.getName()), typeParameters);
        } else if (paramType.isPrimitiveType()) {
            return new SpecifiedType(paramType.toString(), SpecifiedType.TYPE.PRIMITIVE);
        } else if (paramType.isParameterizedType()) {
            ParameterizedType type = (ParameterizedType) paramType;
            return getFullyQualifiedTypeName(type.getType(), resolver, typeParameters);
        } else if (paramType.isArrayType()) {
            ArrayType type = (ArrayType) paramType;
            return new SpecifiedType(
                    String.join("", Collections.nCopies(type.getDimensions(), "[")) + getFullyQualifiedTypeName(type.getElementType(), resolver, typeParameters).descriptor,
                    SpecifiedType.TYPE.ARRAY
            );
        } else {
            LOGGER.error("Unhandled parameter type " + paramType.getClass().getName());
            return null;
        }
    }

    /**
     * Creates a method signature. This differs from a method descriptor in that it contains generic type information
     * instead of the types being erased.
     *
     * @param method The method to create a signature for
     * @param resolver The resolver
     * @param typeParameters The type parameters to be used in the resolver
     * @return A method signature for the specified method
     */
    private static String createMethodSignature(MethodDeclaration method, Resolver resolver, Set<String> typeParameters) {
        SignatureWriter signature = new SignatureWriter();
        for (Object typeParameter : method.typeParameters()) {
            TypeParameter param = (TypeParameter) typeParameter;
            signature.visitFormalTypeParameter(String.valueOf(param.getName()));
            List<?> typeBounds = param.typeBounds();
            if (typeBounds.size() > 0) { // it has bounds, use them
                for (int i = 0, typeBoundsSize = typeBounds.size(); i < typeBoundsSize; i++) {
                    Object typeBound = typeBounds.get(i);
                    Type bound = (Type) typeBound;

                    if (i != 0) { // visitFormalTypeParameter adds the first ':', but all subsequent bounds also need a ':' proceeding
                        signature.visitInterfaceBound();
                    }
                    addSignatureOf(signature, bound, resolver, typeParameters);
                }
            } else { // bound it to object
                signature.visitClassType("java/lang/Object");
                signature.visitEnd();
            }
        }

        for (Object parameter : method.parameters()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) parameter;
            Type paramType = param.getType();

            signature.visitParameterType();
            addSignatureOf(signature, paramType, resolver, typeParameters);
        }

        Type returnTy = method.getReturnType2();
        if (returnTy == null) {
            return null;
        }
        signature.visitReturnType();
        addSignatureOf(signature, returnTy, resolver, typeParameters);

        String s = signature.toString();
        return s;
    }

    /**
     * Adds the signature of the specified type to the supplied {@link SignatureWriter}
     *
     * @param signature The signature to write to
     * @param paramType The type to find and add the signature of
     * @param resolver The resolver
     * @param typeParameters The type parameters to be used in the resolver
     */
    private static void addSignatureOf(SignatureWriter signature, Type paramType, Resolver resolver, Set<String> typeParameters) {
        if (paramType.isSimpleType()) {
            SimpleType simpleType = (SimpleType) paramType;
            SpecifiedType specifiedType = resolver.resolveClassWithTypeParameters(String.valueOf(simpleType.getName()), typeParameters);
            if (specifiedType == null) { // failed to resolve the type, we just use Object as a placeholder
                specifiedType = new SpecifiedType("java.lang.Object", SpecifiedType.TYPE.CLASS);
            }
            if (specifiedType.type == SpecifiedType.TYPE.GENERIC) {
                signature.visitTypeVariable(specifiedType.fullyQualifiedName);
            } else { // type must be a class
                signature.visitClassType(specifiedType.fullyQualifiedName.replace('.', '/'));
                signature.visitEnd();
            }
        } else if (paramType.isPrimitiveType()) {
            signature.visitBaseType(DescriptorUtils.primitiveToDescriptor(paramType.toString()));
        } else if (paramType.isParameterizedType()) {
            ParameterizedType type = (ParameterizedType) paramType;
            Type baseType = type.getType();
            if (baseType.isSimpleType()) {
                // We don't recurse here passing baseType as that would add a ';' before the type parameters
                // incorrect "T;<V>;"  correct "T<V>;"
                SimpleType simpleType = (SimpleType) baseType;
                SpecifiedType specifiedType = resolver.resolveClassWithTypeParameters(String.valueOf(simpleType.getName()), typeParameters);
                if (specifiedType == null) { // failed to resolve the type, we just use Object as a placeholder
                    specifiedType = new SpecifiedType("java.lang.Object", SpecifiedType.TYPE.CLASS);
                }
                signature.visitClassType(specifiedType.fullyQualifiedName.replace('.', '/'));
            } else {
                throw new RuntimeException("ParameterizedType contained non-SimpleType");
            }

            for (Object typeArgument : type.typeArguments()) {
                signature.visitTypeArgument('='); // '=' here denotes a non-wildcard type argument, which we add on the lines below
                Type typeArg = (Type) typeArgument;
                addSignatureOf(signature, typeArg, resolver, typeParameters);
            }
            signature.visitEnd();
        } else if (paramType.isWildcardType()) {
            signature.visitTypeArgument();
        } else if (paramType.isArrayType()) {
            ArrayType type = (ArrayType) paramType;
            for (int i = 0; i < type.getDimensions(); i++)
                signature.visitArrayType();
            addSignatureOf(signature, type.getElementType(), resolver, typeParameters);
        } else {
            throw new RuntimeException("Unhandled parameter type " + paramType.getClass().getName());
        }
    }

    private static ASTParser createASTParser(Set<File> dependencyClasses, Set<File> projectClasses) {
        ASTParser parser = ASTParser.newParser(JLS18);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);

        dependencyClasses = dependencyClasses == null ? new HashSet<>() : dependencyClasses;
        projectClasses = projectClasses == null ? new HashSet<>() : projectClasses;

        parser.setEnvironment(
                dependencyClasses.stream().map(file -> file.toPath().toAbsolutePath().toString()).toArray(String[]::new),
                projectClasses.stream().map(file -> file.toPath().toAbsolutePath().toString()).toArray(String[]::new),
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
                                String annotationClassName = resolver.resolveClass(typeName).descriptor;
                                if (annotationClassName.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
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
                                    interfaces.add(ResolutionUtils.resolveClass(classPackage, String.valueOf(itf.getName()), imports, sourceSetDirectories).descriptor);
                                }
                            }

                            for (String mixinTarget : mixinTargets) {
                                interfacesByTarget.computeIfAbsent(mixinTarget, t -> new HashSet<>()).addAll(interfaces);
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
            return Optional.ofNullable(resolver.resolveClass(name).descriptor);
        }
        return Optional.empty();
    }
}