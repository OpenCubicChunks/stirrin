package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.ty.MethodEntry;
import org.eclipse.jdt.core.dom.*;
import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.objectweb.asm.Type;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.opencubicchunks.stirrin.DescriptorUtils.classToDescriptor;
import static io.github.opencubicchunks.stirrin.Stirrin.LOGGER;
import static io.github.opencubicchunks.stirrin.util.MapUtil.mapKeys;
import static io.github.opencubicchunks.stirrin.util.MethodBindingUtils.*;

public abstract class StirrinTransform implements TransformAction<StirrinTransform.Parameters> {
    interface Parameters extends TransformParameters {
        @Input String getAcceptedJars();
        void setAcceptedJars(String acceptedJars);

        @InputFile Set<String> getConfigs();
        void setConfigs(Set<String> configs);

        @Input long getDebug();
        void setDebug(long l);

        @InputDirectory
        Set<File> getSourceSetDirectories();
        void setSourceSetDirectories(Set<File> sourceSetDirectories);

        @Input
        Map<File, String> getMixinSourceFiles();
        void setMixinSourceFiles(Map<File, String> mixinSourceFiles);
    }

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @InputArtifactDependencies
    public abstract FileCollection getTransitiveDeps();

    @Override
    public void transform(TransformOutputs outputs) {
        File artifactFile = getInputArtifact().get().getAsFile();
        String fileName = artifactFile.getName();
        Pattern acceptedJars = Pattern.compile(getParameters().getAcceptedJars());

        if (acceptedJars.matcher(fileName).matches()) {
            LOGGER.warn(String.format("Found accepted jar: %s", artifactFile));
            LOGGER.debug(String.format("Transitive Deps: %s", getTransitiveDeps().getFiles()));

            String fileNameNoExt = fileName.substring(0, fileName.lastIndexOf("."));
            String outputFileName = fileNameNoExt + "-stirred.jar";

            Set<Path> dependencies = getTransitiveDeps().getFiles().stream().map(File::toPath).collect(Collectors.toSet());
            dependencies.add(artifactFile.toPath());
            Set<Path> sourceSets = getParameters().getSourceSetDirectories().stream().map(File::toPath).collect(Collectors.toSet());
            Parser parser = new Parser(dependencies, sourceSets);

            Map<String, Map<Type, Collection<MethodEntry>>> mixinInterfacesByTarget = 
                    getMixinInterfacesByTarget(parser, mapKeys(getParameters().getMixinSourceFiles(), File::toPath));

            StirrinTransformer.transformMinecraftJar(mixinInterfacesByTarget, artifactFile, outputs.file(outputFileName));

            LOGGER.warn(String.format("transformed %s", outputFileName));
        } else {
            LOGGER.debug(String.format("Rejected jar %s", fileName));
            outputs.file(getInputArtifact());
        }
    }

    private static Map<String, Map<Type, Collection<MethodEntry>>> getMixinInterfacesByTarget(Parser parser, Map<Path, String> mixinSourceFiles) {
        Map<String, Map<Type, Collection<MethodEntry>>> mixinInterfacesByTarget = new HashMap<>();

        Set<String> mixinClasses = new HashSet<>(mixinSourceFiles.values());

        FileASTRequestor requestor = new FileASTRequestor() {
            @Override public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                for (Object type : cu.types()) {
                    getInterfacesFromType((AbstractTypeDeclaration) type, mixinInterfacesByTarget, mixinClasses);
                }
            }
        };

        try {
            String[] sourcePaths = mixinSourceFiles.keySet().stream().map(sourcePath -> sourcePath.toAbsolutePath().toString()).toArray(String[]::new);
            parser.getParser().createASTs(sourcePaths, null, new String[0], requestor, null);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return mixinInterfacesByTarget;
    }

    private static void getInterfacesFromType(AbstractTypeDeclaration abstractTypeDecl, Map<String, Map<Type, Collection<MethodEntry>>> mixinInterfacesByTarget, Set<String> mixinClasses) {
        if (abstractTypeDecl instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) abstractTypeDecl;

            if (!mixinClasses.contains(typeDecl.resolveBinding().getBinaryName())) {
                return;
            }

            Set<String> mixinTargets = getMixinTargetsForType(typeDecl);
            if (!mixinTargets.isEmpty()) { // this type is a mixin and has targets
                Map<Type, Collection<MethodEntry>> interfaceMethodsFromType = getInterfaceMethodsFromType(typeDecl);

                for (String mixinTarget : mixinTargets) {
                    Map<Type, Collection<MethodEntry>> methodsByInterface = mixinInterfacesByTarget.computeIfAbsent(mixinTarget, t -> new HashMap<>());

                    interfaceMethodsFromType.forEach((t, methods) ->
                            methodsByInterface.computeIfAbsent(t, tt -> new HashSet<>()).addAll(methods));
                }
            }

            for (TypeDeclaration innerType : typeDecl.getTypes()) {
                getInterfacesFromType(innerType, mixinInterfacesByTarget, mixinClasses);
            }
        }
    }

    private static Map<Type, Collection<MethodEntry>> getInterfaceMethodsFromType(TypeDeclaration typeDecl) {
        Map<Type, Collection<MethodEntry>> methodsByInterface = new HashMap<>();
        List<?> interfaceTypes = typeDecl.superInterfaceTypes();
        for (Object anInterface : interfaceTypes) {
            if (anInterface instanceof SimpleType) { // TODO: handle parameterized interfaces
                ITypeBinding itf = ((SimpleType) anInterface).resolveBinding();

                if (itf == null || itf.getBinaryName() == null) {
                    LOGGER.error("Cannot resolve interface: " + ((SimpleType) anInterface).getName().toString() + " for Mixin: " + typeDecl.resolveBinding().getQualifiedName());
                    continue;
                }

                Type itfType = Type.getType(classToDescriptor(itf.getBinaryName()));

                for (IMethodBinding method : itf.getDeclaredMethods()) {
                    try {
                        String methodDescriptor = createMethodDescriptor(method);
                        String methodSignature = createMethodSignature(method);

                        List<String> paramNames = getParamNames(method);

                        methodsByInterface.computeIfAbsent(itfType, t -> new ArrayList<>()).add(
                                new MethodEntry(method.getName(), methodDescriptor, methodSignature, paramNames, new ArrayList<>())
                        );
                    } catch (ClassNotFoundException e) {
                        LOGGER.error("Cannot resolve type in method: " + method + " for Interface: " + itf.getQualifiedName());
                    }
                }
            }
        }
        return methodsByInterface;
    }

    private static Set<String> getMixinTargetsForType(TypeDeclaration typeDecl) {
        Set<String> mixinTargets = new HashSet<>();
        for (Object modifier : typeDecl.modifiers()) {
            if (modifier instanceof SingleMemberAnnotation) {
                SingleMemberAnnotation annotation = (SingleMemberAnnotation) modifier;
                if (annotation.resolveTypeBinding().getQualifiedName().equals("org.spongepowered.asm.mixin.Mixin")) {
                    Expression value = annotation.getValue();
                    if (value instanceof TypeLiteral) {
                        mixinTargets.add(value.resolveTypeBinding().getTypeArguments()[0].getBinaryName());
                        break;
                    } else if (value instanceof ArrayInitializer) {
                        for (Object expression : ((ArrayInitializer) value).expressions()) {
                            if (expression instanceof TypeLiteral) {
                                mixinTargets.add(((TypeLiteral) expression).resolveTypeBinding().getBinaryName());
                            } else if (expression instanceof StringLiteral) {
                                mixinTargets.add(((StringLiteral) expression).getLiteralValue());
                            }
                        }
                        break;
                    } else if (value instanceof StringLiteral) {
                        mixinTargets.add(((StringLiteral) value).getLiteralValue());
                    } else {
                        throw new RuntimeException("Unhandled mixin annotation expression");
                    }
                }
            }
        }
        return mixinTargets;
    }
}