package io.github.opencubicchunks.stirrin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.opencubicchunks.stirrin.util.Pair;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        project.afterEvaluate(p -> p.getDependencies().getComponents().withModule("net.minecraft:minecraft-merged-project-root", MinecraftLibrariesRule.class, conf -> {
            List<String> mcLibs = p.getConfigurations().getAt("minecraftLibraries")
                .getDependencies().stream().map(dep -> dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion()).collect(Collectors.toList());
            mcLibs.addAll(p.getConfigurations().getAt("minecraftServerLibraries")
                .getDependencies().stream().map(dep -> dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion()).collect(Collectors.toList()));
            conf.setParams(mcLibs);
        }));


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

    /**
     * @param mixinConfigFilenames mixin config files
     * @param sourceSetContainer SourceSets to search for mixin classes in
     * @return Set of File-ClassName pairs
     */
    public static Set<Pair<Path, String>> findMixinSourceFiles(Set<String> mixinConfigFilenames, SourceSetContainer sourceSetContainer) {
        Map<SourceSet, List<File>> mixinConfigsBySourceSet = findMixinConfigsBySourceSet(mixinConfigFilenames, sourceSetContainer);
        Set<Pair<Path, String>> mixinPairs = new HashSet<>();

        Gson gson = new Gson();
        mixinConfigsBySourceSet.forEach(((sourceSet, mixinConfigs) -> {
            for (File mixinConfig : mixinConfigs) {
                try {
                    System.out.println("Supplied mixin config path: " + mixinConfig);
                    String fileText = Files.readString(mixinConfig.toPath());

                    JsonObject jsonObject = gson.fromJson(fileText, JsonObject.class);
                    String packagePrefix = jsonObject.get("package").getAsString();
                    @SuppressWarnings("unchecked")
                    List<String> mixins = gson.fromJson(jsonObject.get("mixins"), List.class);
                    @SuppressWarnings("unchecked")
                    List<String> clientMixins = gson.fromJson(jsonObject.get("client"), List.class);
                    @SuppressWarnings("unchecked")
                    List<String> serverMixins = gson.fromJson(jsonObject.get("server"), List.class);

                    if (clientMixins != null)
                        mixins.addAll(clientMixins);
                    if (serverMixins != null)
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

    private static Collection<Pair<Path, String>> findMixinClasses(SourceSet sourceSet, List<String> mixinClasses) {
        List<Pair<Path, String>> mixinSourceFiles = new ArrayList<>();

        for (String mixinClass : mixinClasses) {
            String outerClass = mixinClass;
            int innerClassIdx = mixinClass.indexOf("$");
            if (innerClassIdx != -1)
                outerClass = mixinClass.substring(0, innerClassIdx);

            String mixinClassFilename = outerClass.replace('.', File.separatorChar) + ".java";
            for (File resource : sourceSet.getJava().getSrcDirs()) {
                Path sourcePath = resource.toPath().resolve(mixinClassFilename);
                if (Files.exists(sourcePath)) {
                    mixinSourceFiles.add(new Pair<>(sourcePath, mixinClass));
                }
            }
        }
        return mixinSourceFiles;
    }

    public static class MinecraftLibrariesRule implements ComponentMetadataRule {
        private final List<String> dependencies;

        @Inject
        public MinecraftLibrariesRule(List<String> dependencies) {
            this.dependencies = dependencies;
        }

        @Override public void execute(ComponentMetadataContext context) {
            context.getDetails().allVariants(variantMetadata ->
                variantMetadata.withDependencies(directDependencyMetadata ->
                    dependencies.forEach(directDependencyMetadata::add)
                )
            );
        }
    }
}