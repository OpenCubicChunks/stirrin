package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.ty.MethodEntry;
import io.github.opencubicchunks.stirrin.ty.SpecifiedType;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

import static io.github.opencubicchunks.stirrin.DescriptorUtils.classToDescriptor;
import static io.github.opencubicchunks.stirrin.Stirrin.LOGGER;
import static io.github.opencubicchunks.stirrin.util.JarIO.loadClasses;
import static io.github.opencubicchunks.stirrin.util.JarIO.saveAsJar;
import static org.objectweb.asm.Opcodes.*;

public class StirrinTransformer {
    public static void transformMinecraftJar(Map<String, Set<String>> interfacesByMixinClass, Map<String, List<MethodEntry>> methodsByInterface, File minecraftJar, File outputCoreJar) {
        try {
            List<ClassNode> classNodes = loadClasses(minecraftJar);
            for (ClassNode classNode : classNodes) {
                Set<String> interfacesToAdd = interfacesByMixinClass.get(classToDescriptor(classNode.name));
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
            for (SpecifiedType parameter : methodEntry.parameters) {
                String descriptor = parameter.descriptor;
                // TODO: get type parameters working in output
                for (String typeParameter : methodEntry.typeParameters) {
                    if (descriptor.equals(typeParameter)) {
                        descriptor = "Ljava/lang/Object;";
                        break;
                    }
                }
                params.add(Type.getType(descriptor));
            }
            String methodDescriptor = Type.getMethodDescriptor(Type.getType(methodEntry.returnType.descriptor), params.toArray(new Type[0]));
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
        String descriptor = classToDescriptor(classNode.name);
        method.localVariables.add(new LocalVariableNode("this", descriptor, null, new LabelNode(), new LabelNode(), 0));

        method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(ASM9, classToDescriptor(StirrinStub.class.getName())));

        // TODO: get type parameters working in output

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
        Set<String> interfacesAdded = new HashSet<>();
        for (String itf : interfacesToAdd) {
            String substring = itf.substring(1, itf.length() - 1); // classNode.interfaces does NOT contain L and ;
            if (!classNode.interfaces.contains(substring)) {
                classNode.interfaces.add(substring);
                interfacesAdded.add(itf);
            }
        }

        // For each interface added, add it to the class signature.
        if (classNode.signature != null && !classNode.signature.isEmpty()) {
            StringBuilder sb = new StringBuilder(classNode.signature);
            for (String entry : interfacesAdded) {
                sb.append(entry);
            }
            classNode.signature = sb.toString();
        }

        for (String interfaceAdded : interfacesToAdd) {
            LOGGER.warn(String.format("%s: Added interface %s", classNode.name, interfaceAdded));
        }
    }
}
