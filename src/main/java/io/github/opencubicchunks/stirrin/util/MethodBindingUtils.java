package io.github.opencubicchunks.stirrin.util;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.objectweb.asm.signature.SignatureWriter;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class MethodBindingUtils {

    /**
     * Creates a method methodSignature. This differs from a method descriptor in that it contains generic type information
     * instead of the types being erased.
     *
     * @param method The method to create a methodSignature for
     * @return A method methodSignature for the specified method
     */
    public static String createMethodSignature(IMethodBinding method) {
        SignatureWriter signature = new SignatureWriter();
        for (ITypeBinding param : method.getTypeParameters()) {
            signature.visitFormalTypeParameter(String.valueOf(param.getName()));
            ITypeBinding[] typeBounds = param.getTypeBounds();
            if (typeBounds.length > 0) { // it has bounds, use them
                for (int i = 0, typeBoundsSize = typeBounds.length; i < typeBoundsSize; i++) {
                    ITypeBinding bound = typeBounds[i];
                    if (i != 0) { // visitFormalTypeParameter adds the first ':', but all subsequent bounds also need a ':' proceeding
                        signature.visitInterfaceBound();
                    }
                    addSignatureOf(signature, bound);
                }
            } else { // doesn't have bounds? bind it to object
                signature.visitClassType("java/lang/Object");
                signature.visitEnd();
            }
        }

        for (ITypeBinding param : method.getParameterTypes()) {
            signature.visitParameterType();
            addSignatureOf(signature, param);
        }

        signature.visitReturnType();
        addSignatureOf(signature, method.getReturnType());

        return signature.toString();
    }

    /**
     * Adds the methodSignature of the specified type to the supplied {@link SignatureWriter}
     *
     * @param signature The methodSignature to write to
     * @param paramType The type to find and add the methodSignature of
     */
    private static void addSignatureOf(SignatureWriter signature, ITypeBinding paramType) {
        if (paramType.isGenericType() || paramType.isTypeVariable()) {
            signature.visitTypeVariable(paramType.getName());
        } else if (paramType.isWildcardType()) {
            signature.visitTypeArgument();
        } else if (paramType.isParameterizedType()) {
            // We don't recurse here passing baseType as that would add a ';' before the type parameters
            // incorrect "T;<V>;", correct "T<V>;"
            signature.visitClassType(paramType.getBinaryName().replace('.', '/'));

            for (ITypeBinding typeArgument : paramType.getTypeArguments()) {
                signature.visitTypeArgument('='); // '=' here denotes a non-wildcard type argument, which we add on the lines below
                addSignatureOf(signature, typeArgument);
            }
            signature.visitEnd();
        } else if (paramType.isArray()) {
            int dimensions = paramType.getDimensions();
            for (int i = 0; i < dimensions; i++)
                signature.visitArrayType();
            addSignatureOf(signature, paramType.getElementType());
        } else if (paramType.isPrimitive()) {
            signature.visitBaseType(paramType.getBinaryName().charAt(0));
        } else if (paramType.isClass() || paramType.isEnum() || paramType.isRecord() || paramType.isInterface()) {
            signature.visitClassType(paramType.getBinaryName().replace('.', '/'));
            signature.visitEnd();
        } else {
            throw new RuntimeException("Unhandled parameter type");
        }
    }

    public static String createMethodDescriptor(IMethodBinding method) {
        SignatureWriter signature = new SignatureWriter();

        for (ITypeBinding param : method.getParameterTypes()) {
            signature.visitParameterType();
            addDescriptorOf(signature, param);
        }

        signature.visitReturnType();
        addDescriptorOf(signature, method.getReturnType());

        return signature.toString();
    }

    private static void addDescriptorOf(SignatureWriter signature, ITypeBinding paramType) {
        if (paramType.isGenericType() || paramType.isTypeVariable()) {
            signature.visitClassType(Object.class.getName().replace('.', '/'));
            signature.visitEnd();
        } else if (paramType.isWildcardType()) {
            signature.visitTypeArgument();
        } else if (paramType.isParameterizedType()) {
            signature.visitClassType(paramType.getBinaryName().replace('.', '/'));
            signature.visitEnd();
        } else if (paramType.isArray()) {
            int dimensions = paramType.getDimensions();
            for (int i = 0; i < dimensions; i++)
                signature.visitArrayType();
            addDescriptorOf(signature, paramType.getElementType());
        } else if (paramType.isPrimitive()) {
            signature.visitBaseType(paramType.getBinaryName().charAt(0));
        } else if (paramType.isClass() || paramType.isEnum() || paramType.isRecord() || paramType.isInterface()) {
            signature.visitClassType(paramType.getBinaryName().replace('.', '/'));
            signature.visitEnd();
        } else {
            throw new RuntimeException("Unhandled parameter type");
        }
    }

    private static final Field BINDING_FIELD;
    static {
        Field field;
        try {
            Class<?> clazz = Class.forName("org.eclipse.jdt.core.dom.MethodBinding");
            field = clazz.getDeclaredField("binding");
            field.setAccessible(true);
        } catch (NoSuchFieldException | ClassNotFoundException ignored) {
            field = null;
        }
        BINDING_FIELD = field;
    }

    @Nullable
    public static List<String> getParamNames(IMethodBinding method) {
        // TODO: figure out how to do this without reflection
        try {
            if (BINDING_FIELD == null) {
                return null;
            }
            Object o = BINDING_FIELD.get(method);
            if (!(o instanceof MethodBinding)) {
                return null;
            }

            MethodBinding methodBinding = (MethodBinding) o;
            char[][] parameterNames = methodBinding.parameterNames;

            List<String> paramNames = new ArrayList<>();
            for (char[] parameterName : parameterNames) {
                paramNames.add(String.valueOf(parameterName));
            }
            return paramNames;
        } catch (IllegalAccessException ignored) { }
        return null;
    }
}
