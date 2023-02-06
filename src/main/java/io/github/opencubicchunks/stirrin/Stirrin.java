package io.github.opencubicchunks.stirrin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class Stirrin implements Plugin<Project> {
    public static final Logger LOGGER = LoggerFactory.getLogger(Stirrin.class.getName());

    @Override
    public void apply(Project project) {
        DependencyHandler dependencies = project.getDependencies();
        Attribute<String> artifactType = Attribute.of("artifactType", String.class);
        Attribute<Boolean> mixinInterfaces = Attribute.of("mixinInterfaces", Boolean.class);

        ConfigurationContainer configurations = project.getConfigurations();

        for (Configuration configuration : configurations) {
            if (configuration.isCanBeResolved()) {
                configuration.getAttributes().attribute(mixinInterfaces, true);
            }
        }

        dependencies.getAttributesSchema().attribute(mixinInterfaces);
        // set all jar dependencies to default to mixinInterfaces false
        dependencies.getArtifactTypes().getByName("jar").getAttributes().attribute(mixinInterfaces, false);

        dependencies.registerTransform(StirrinTransform.class, transformSpec -> {
            transformSpec.getFrom().attribute(mixinInterfaces, false).attribute(artifactType, "jar");
            transformSpec.getTo().attribute(mixinInterfaces, true).attribute(artifactType, "jar");

            StirrinTransform.Parameters parameters = transformSpec.getParameters();

            StirrinExtension extension = new StirrinExtension(project, parameters);
            project.getExtensions().add("stirrin", extension);
        });
    }

    public static List<Pair<File, String>> findMixinClassFiles(Set<String> mixinConfigFilenames, SourceSetContainer sourceSetContainer) {
        Map<SourceSet, List<File>> mixinConfigsBySourceSet = findMixinConfigsBySourceSet(mixinConfigFilenames, sourceSetContainer);
        List<Pair<File, String>> mixinPairs = new ArrayList<>();

        Gson gson = new Gson();
        mixinConfigsBySourceSet.forEach(((sourceSet, mixinConfigs) -> {
            for (File mixinConfig : mixinConfigs) {
                try {
                    System.out.println("Supplied mixin config path: " + mixinConfig);
                    String fileText = new String(Files.readAllBytes(mixinConfig.toPath()), StandardCharsets.UTF_8);

                    JsonObject jsonObject = gson.fromJson(fileText, JsonObject.class);
                    String packagePrefix = jsonObject.get("package").getAsString();
                    @SuppressWarnings("unchecked")
                    List<String> mixins = gson.fromJson(jsonObject.get("mixins"), List.class);
                    @SuppressWarnings("unchecked")
                    List<String> clientMixins = gson.fromJson(jsonObject.get("client"), List.class);
                    @SuppressWarnings("unchecked")
                    List<String> serverMixins = gson.fromJson(jsonObject.get("server"), List.class);

                    mixins.addAll(clientMixins);
                    mixins.addAll(serverMixins);

                    mixins = mixins.stream().map(className -> packagePrefix + "." + className).collect(Collectors.toList());
                    mixinPairs.addAll(findMixinClasses(sourceSet, mixins));
                } catch (IOException e) {
                    throw new InvalidUserDataException(String.format("Could not parse mixin config file %s", mixinConfig), e);
                }
            }
        }));

        return mixinPairs;
    }

    private static Map<SourceSet, List<File>> findMixinConfigsBySourceSet(Set<String> mixinConfigFilenames, SourceSetContainer sourceSetContainer) {
        Map<SourceSet, List<File>> mixinConfigsBySourceSet = new IdentityHashMap<>();

        for (String mixinConfigFilename : mixinConfigFilenames) {
            for (SourceSet sourceSet : sourceSetContainer) {
                List<File> mixinFiles = mixinConfigsBySourceSet.computeIfAbsent(sourceSet, ss -> new ArrayList<>());
                for (File resource : sourceSet.getResources().getSrcDirs()) {
                    File configFile = new File(resource, mixinConfigFilename);
                    if (configFile.exists()) {
                        mixinFiles.add(configFile);
                    }
                }
            }
        }
        return mixinConfigsBySourceSet;
    }

    private static List<Pair<File, String>> findMixinClasses(SourceSet sourceSet, List<String> mixinClasses) {
        List<Pair<File, String>> mixinClassFilenames = new ArrayList<>();

        for (String mixinClass : mixinClasses) {
            String mixinClassFilename = mixinClass.replace('.', File.separatorChar) + ".java";
            for (File resource : sourceSet.getJava().getSrcDirs()) {
                File configFile = new File(resource, mixinClassFilename);
                if (configFile.exists()) {
                    mixinClassFilenames.add(new Pair<>(configFile, mixinClass));
                }
            }
        }
        return mixinClassFilenames;
    }
}