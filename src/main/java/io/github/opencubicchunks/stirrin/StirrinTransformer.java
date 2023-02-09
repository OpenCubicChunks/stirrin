package io.github.opencubicchunks.stirrin;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.opencubicchunks.stirrin.Stirrin.LOGGER;
import static io.github.opencubicchunks.stirrin.util.JarIO.loadClasses;
import static io.github.opencubicchunks.stirrin.util.JarIO.saveAsJar;
import static org.objectweb.asm.Opcodes.*;

public class StirrinTransformer {
    public static void transformMinecraftJar(Map<String, Set<String>> interfacesByMixinClass, Map<String, List<MethodEntry>> methodsByInterface, File minecraftJar, File outputCoreJar) {
        try {
            List<ClassNode> classNodes = loadClasses(minecraftJar);
            for (ClassNode classNode : classNodes) {
                String className = classNode.name.replace("/", ".");

                Set<String> interfacesToAdd = interfacesByMixinClass.get(className);
                if (interfacesToAdd == null) {
                    continue;
                }

                addInterfacesToClass(classNode, interfacesToAdd);

                for (String itf : interfacesToAdd) {
                    List<MethodEntry> methodEntries = methodsByInterface.get(itf);
                    if (methodEntries != null)
                        addInterfaceMethodsStubs(classNode, methodEntries);
                }
            }
            saveAsJar(classNodes, minecraftJar, outputCoreJar);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * For each method entry supplied, a stub method is added to the class. See {@link StirrinTransformer#createMethodStub(ClassNode, MethodEntry, String)}
     *
     * @param classNode The {@link ClassNode} to modify
     * @param methodEntries List of method entries to add to the {@link ClassNode}
     */
    private static void addInterfaceMethodsStubs(ClassNode classNode, List<MethodEntry> methodEntries) {
        for (MethodEntry methodEntry : methodEntries) {
            //TODO: add thrown exceptions to signature
            List<Type> params = new ArrayList<>();
            for (MethodEntry.Parameter parameter : methodEntry.parameters) {
                String descriptor = parameter.descriptor;
                // TODO: get type parameters working in output
                for (String typeParameter : methodEntry.typeParameters) {
                    if (descriptor.equals("L" + typeParameter + ";")) {
                        descriptor = "Ljava/lang/Object;";
                        break;
                    }
                }
                params.add(Type.getType(descriptor));
            }
            // TODO: get type parameters working in output
//        for (String typeParameter : methodEntry.typeParameters) {
//            method.visibleTypeAnnotations.add(new TypeAnnotationNode(ASM9, TypeReference.METHOD_TYPE_PARAMETER, null, typeParameter));
//        }

            String methodDescriptor = Type.getMethodDescriptor(Type.getType(signatureToDescriptor(methodEntry.returnType)), params.toArray(new Type[0]));
            MethodNode method = createMethodStub(classNode, methodEntry, methodDescriptor);

            classNode.methods.add(method);
            LOGGER.debug(classNode.name + ": Added stub method: " + methodEntry.name + " | " + methodDescriptor);
        }
    }

    /**
     * Creates a method stub which throws a {@link RuntimeException} with some information.
     */
    private static MethodNode createMethodStub(ClassNode classNode, MethodEntry methodEntry, String methodDescriptor) {
        MethodNode method = new MethodNode(ASM9, ACC_PUBLIC, methodEntry.name, methodDescriptor, null, null);
        String descriptor = signatureToDescriptor(classNode.name);
        method.localVariables.add(new LocalVariableNode("this", descriptor, null, new LabelNode(), new LabelNode(), 0));

        method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(ASM9, signatureToDescriptor(StirrinStub.class.getName())));

        if (method.visibleTypeAnnotations == null) {
            method.visibleTypeAnnotations = new ArrayList<>();
        }

        method.instructions.add(new TypeInsnNode(NEW, "java/lang/RuntimeException"));
        method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new LdcInsnNode("This stub should only exist in a dev environment. If this exception is thrown stubs were not removed before mixin applied!"));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, RuntimeException.class.getName().replace('.', '/'), "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(ATHROW));
        method.maxStack = 3;
        method.maxLocals = 2 + methodEntry.parameters.size(); // 1 for this, 1 for the error, one for each param
        return method;
    }

    /**
     * @param classNode The {@link ClassNode} to modify
     * @param interfacesToAdd The interfaces to add to the {@link ClassNode}
     */
    private static void addInterfacesToClass(ClassNode classNode, Set<String> interfacesToAdd) {
        if (classNode.interfaces == null) {
            classNode.interfaces = new ArrayList<>();
        }

        // Add each interface to the classNode's interfaces list without duplicates
        interfacesToAdd = interfacesToAdd.stream().map(interfaceString -> interfaceString.replace(".", "/")).collect(Collectors.toSet());
        Set<String> interfacesAdded = new HashSet<>();
        for (String itf : interfacesToAdd) {
            if (!classNode.interfaces.contains(itf)) {
                classNode.interfaces.add(itf);
                interfacesAdded.add(itf);
            }
        }

        // For each interface added, add it to the class signature.
        if (classNode.signature != null && !classNode.signature.isEmpty()) {
            StringBuilder sb = new StringBuilder(classNode.signature);
            for (String entry : interfacesAdded) {
                sb.append('L').append(entry).append(';');
            }
            classNode.signature = sb.toString();
        }

        for (String interfaceAdded : interfacesToAdd) {
            LOGGER.debug(String.format("%s: Added interface %s", classNode.name, interfaceAdded));
        }
    }

    public static String signatureToDescriptor(String sig) {
        // Trivial primitive types
        switch (sig) {
            case "void":
                return "V";
            case "boolean":
                return "Z";
            case "char":
                return "C";
            case "byte":
                return "B";
            case "short":
                return "S";
            case "int":
                return "I";
            case "float":
                return "F";
            case "long":
                return "J";
            case "double":
                return "D";
        }

        if (sig.contains("void") ||
                sig.contains("boolean") ||
                sig.contains("char") ||
                sig.contains("byte") ||
                sig.contains("short") ||
                sig.contains("int") ||
                sig.contains("float") ||
                sig.contains("long") ||
                sig.contains("double")
        ) {

        }

        if (sig.contains("[")) {
            // Array signatures
            sig = sig.replace("void", "V");
            sig = sig.replace("boolean", "Z");
            sig = sig.replace("char", "C");
            sig = sig.replace("byte", "B");
            sig = sig.replace("short", "S");
            sig = sig.replace("int", "I");
            sig = sig.replace("float", "F");
            sig = sig.replace("long", "J");
            sig = sig.replace("double", "D");
        } else {
            // Classes or type parameters
            sig = "L" + sig.replace('.', '/') + ";";
        }

        return sig;
    }
}
