package io.github.opencubicchunks.stirrin.util;

import io.github.opencubicchunks.stirrin.StirrinTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.objectweb.asm.Opcodes.ASM9;

public class JarIO {
    /**
     * Loads all class entries from a jar outputJar
     */
    public static List<ClassNode> loadClasses(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile); Stream<JarEntry> str = jar.stream()) {
            return str.map(z -> readJarClasses(jar, z)).filter(Optional::isPresent).map(Optional::get)
                    .map(JarIO::classNodeFromBytes)
                    .collect(Collectors.toList());
        }
    }

    private static ClassNode classNodeFromBytes(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode(ASM9);
        reader.accept(classNode, 0);
        return classNode;
    }

    private static Optional<byte[]> readJarClasses(JarFile jar, JarEntry entry) {
        String name = entry.getName();
        try (InputStream inputStream = jar.getInputStream(entry)){
            if (name.endsWith(".class")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(inputStream.available());
                byte[] buf = new byte[4096];
                int read;
                while ((read = inputStream.read(buf)) > 0) {
                    output.write(buf, 0, read);
                }
                byte[] bytes = output.toByteArray();
                return Optional.of(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.empty();
    }

    /**
     * Takes a list of class nodes and writes them to the output outputJar
     *
     * All non-class entries from the specified input jar are also written to the output jar
     */
    public static void saveAsJar(List<ClassNode> classNodes, File inputJar, File outputJar) {
        try (JarOutputStream outputStream = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar.toPath())))) {
            Map<String, byte[]> nonClassEntries = loadNonClasses(inputJar);

            // write all non-class entries from the input jar
            for (Map.Entry<String, byte[]> e : nonClassEntries.entrySet()) {
                outputStream.putNextEntry(new ZipEntry(e.getKey()));
                outputStream.write(e.getValue());
                outputStream.closeEntry();
            }

            // write all class nodes
            for (ClassNode classNode : classNodes) {
                ClassWriter writer = new ClassWriter(0);
                classNode.accept(writer);
                byte[] bytes = writer.toByteArray();

                outputStream.putNextEntry(new ZipEntry(classNode.name + ".class"));
                outputStream.write(bytes);
                outputStream.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Loads all NON-class entries from a jar outputJar
     */
    private static void readNonJars(JarFile jar, JarEntry entry, Map<String, byte[]> nonClasses) {
        String name = entry.getName();
        try (InputStream inputStream = jar.getInputStream(entry)){
            if (!name.endsWith(".class")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(inputStream.available());
                byte[] buf = new byte[4096];
                int read;
                while ((read = inputStream.read(buf)) > 0) {
                    output.write(buf, 0, read);
                }
                byte[] bytes = output.toByteArray();
                nonClasses.put(name, bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, byte[]> loadNonClasses(File jarFile) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        JarFile jar = new JarFile(jarFile);
        Stream<JarEntry> str = jar.stream();
        str.forEach(z -> readNonJars(jar, z, classes));
        jar.close();
        return classes;
    }
}
