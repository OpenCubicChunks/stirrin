package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.ty.MethodEntry;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import javax.annotation.Nullable;
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
    /**
     * @param mixinInterfacesByTarget Map from Mixin target class, to a map of methods by interface
     * @param input The input minecraft jar
     * @param output The output minecraft jar
     */
    public static void transformMinecraftJar(Map<String, Map<Type, Collection<MethodEntry>>> mixinInterfacesByTarget, File input, File output) {
        try {
            List<ClassNode> classNodes = loadClasses(input);

            for (ClassNode classNode : classNodes) {
                Map<Type, Collection<MethodEntry>> mixinInterfaces = mixinInterfacesByTarget.get(classNode.name.replace('/', '.'));
                if (mixinInterfaces == null) {
                    continue;
                }

                // Methods are added first, as this method checks the current interfaces array of the classnode to see which
                // interfaces to add
                addInterfaceMethodsStubs(classNode, mixinInterfaces);

                Set<Type> interfacesToAdd = mixinInterfaces.keySet();
                addInterfacesToClass(classNode, interfacesToAdd);
            }
            saveAsJar(classNodes, input, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * For each method entry supplied, a stub method is added to the class. See {@link StirrinTransformer#createMethodStub(ClassNode, MethodEntry, String, String)}
     *
     * @param classNode The {@link ClassNode} to modify
     * @param methodEntriesByInterface Method entries by interface they come from, to add to the {@link ClassNode}
     */
    private static void addInterfaceMethodsStubs(ClassNode classNode, Map<Type, Collection<MethodEntry>> methodEntriesByInterface) {
        methodEntriesByInterface.forEach((itf, methodEntries) -> {
            if (classNode.interfaces.contains(itf.getInternalName())) {
                LOGGER.warn(String.format("Class %s already implements interface %s, it will not be applied", classNode.name, itf.getClassName()));
                return;
            }
            for (Iterator<MethodEntry> it = methodEntries.iterator(); it.hasNext(); ) {
                MethodEntry methodEntry = it.next();

                for (MethodNode method : classNode.methods) {
                    if (methodEntry.name.equals(method.name) && methodEntry.descriptor.equals(method.desc)) {
                        it.remove();
                        // TODO: make this an error log once super-interfaces are properly taken into account
                        LOGGER.warn(String.format("Class %s | Not adding method with identical descriptor to existing method. Method: %s%s", classNode.name, method.name, method.desc));
                        break;
                    }
                }
            }

            for (MethodEntry methodEntry : methodEntries) {
                MethodNode method = createMethodStub(classNode, methodEntry, methodEntry.descriptor, methodEntry.signature);

                classNode.methods.add(method);
                LOGGER.info(classNode.name + ": Added stub method: " + methodEntry.name + " | " + methodEntry.descriptor);
            }
        });
    }

    /**
     * Creates a method stub which throws a {@link RuntimeException} with some information.
     */
    private static MethodNode createMethodStub(ClassNode classNode, MethodEntry methodEntry, String methodDescriptor, @Nullable String methodSignature) {
        MethodNode method = new MethodNode(ASM9, ACC_PUBLIC, methodEntry.name, methodDescriptor, methodSignature, null);
        String descriptor = classToDescriptor(classNode.name);
        method.localVariables.add(new LocalVariableNode("this", descriptor, null, new LabelNode(), new LabelNode(), 0));

        method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(ASM9, classToDescriptor(StirrinStub.class.getName())));

        method.instructions.add(new TypeInsnNode(NEW, RuntimeException.class.getName().replace('.', '/')));
        method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new LdcInsnNode("This stub should only exist in a dev environment. If this exception is thrown stubs were not removed before mixin applied!"));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, RuntimeException.class.getName().replace('.', '/'), "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(ATHROW));
        method.maxStack = 3;
        method.maxLocals = 10; // + methodEntry.method.getArgumentTypes().length; // 1 for this, 1 for the error, one for each param //TODO: reimplement

        if (methodEntry.parameterNames != null) {
            method.parameters = new ArrayList<>();
            for (String parameterName : methodEntry.parameterNames) {
                method.parameters.add(new ParameterNode(parameterName, 0));
            }
        }

        method.exceptions = new ArrayList<>();
        method.exceptions.addAll(methodEntry.exceptions);

        return method;
    }

    /**
     * @param classNode The {@link ClassNode} to modify
     * @param interfacesToAdd The interfaces to add to the {@link ClassNode}
     */
    private static void addInterfacesToClass(ClassNode classNode, Set<Type> interfacesToAdd) {
        if (classNode.interfaces == null) {
            classNode.interfaces = new ArrayList<>();
        }

        // Add each interface to the classNode's interfaces list without duplicates
        Set<String> interfacesAdded = new HashSet<>();
        for (Type itf : interfacesToAdd) {
            String internalName = itf.getInternalName(); // classNode.interfaces does NOT contain L and ;
            if (!classNode.interfaces.contains(internalName)) {
                classNode.interfaces.add(internalName);
                interfacesAdded.add(itf.getDescriptor());
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

        for (String interfaceAdded : interfacesAdded) {
            LOGGER.info(String.format("%s: Added interface %s", classNode.name, interfaceAdded));
        }
    }
}
