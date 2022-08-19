package io.github.opencubicchunks.stirrin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import io.github.opencubicchunks.stirrin.StirrinTransform.Parameters;
import org.gradle.api.Project;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSetContainer;

public class StirrinExtension {
    private final Project project;
    private final Parameters parameters;

    public StirrinExtension(Project project, Parameters parameters) {
        this.project = project;
        this.parameters = parameters;
    }

    public void setAcceptedJars(String acceptedJars) {
        this.parameters.setAcceptedJars(acceptedJars);
    }

    public void setConfigs(Set<String> mixinConfigFiles) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(JavaPluginExtension.class).getSourceSets();
        List<Pair<File, String>> mixinClassFiles = Stirrin.findMixinClassFiles(mixinConfigFiles, sourceSets);

        Map<String, Set<String>> interfacesByMixinClass = new HashMap<>();

        for (Pair<File, String> mixinPair : mixinClassFiles) {
            try {
                String classSource = new String(Files.readAllBytes( mixinPair.l().toPath()), StandardCharsets.UTF_8);
                List<String> imports = JavaUtils.getImports(classSource);
                Set<String> interfaces = JavaUtils.getInterfaces(imports, sourceSets, classSource);
                Matcher matcher = JavaUtils.MIXIN_TARGET_PATTERN.matcher(classSource);

                if (matcher.find()) {
                    String targetName = matcher.group(1);
                    targetName = targetName.substring(0, targetName.length() - ".class".length());
                    interfacesByMixinClass.put(JavaUtils.resolveClass(targetName, imports, sourceSets), interfaces);
                } else {
                    int ads = 0;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(String.format("Failed to parse class file %s", mixinPair.l()), e);
            }
        }

        this.parameters.setConfigs(mixinConfigFiles);
        this.parameters.setInterfacesByMixinClass(interfacesByMixinClass);
    }

    public void setDebug(boolean value) {
        this.parameters.setDebug(value ? System.nanoTime() : 0);
    }
}