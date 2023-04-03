package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.StirrinTransform.Parameters;
import io.github.opencubicchunks.stirrin.util.Pair;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.opencubicchunks.stirrin.util.MapUtil.mapKeys;

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

        Set<Pair<Path, String>> mixinSourceFiles = Stirrin.findMixinSourceFiles(mixinConfigFiles, sourceSets);
        Map<Path, String> sourceFiles = new HashMap<>();
        mixinSourceFiles.forEach(pair -> sourceFiles.put(pair.l(), pair.r()));

        Set<Path> sourceSetDirectories = new HashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            sourceSetDirectories.addAll(sourceSet.getJava().getSrcDirs().stream().map(File::toPath).collect(Collectors.toSet()));
        }

        this.parameters.setConfigs(mixinConfigFiles);
        this.parameters.setMixinSourceFiles(mapKeys(sourceFiles, Path::toFile));
        this.parameters.setSourceSetDirectories(sourceSetDirectories.stream().map(Path::toFile).collect(Collectors.toSet()));
    }

    public void setDebug(boolean value) {
        this.parameters.setDebug(value ? System.nanoTime() : 0);
    }

    @CacheableRule
    public static abstract class MinecraftRule implements ComponentMetadataRule {
        private final String dependency;

        @Inject
        public MinecraftRule(String dependency) {
            this.dependency = dependency;
        }

        @Override public void execute(ComponentMetadataContext context) {
            context.getDetails().allVariants(variantMetadata ->
                variantMetadata.withDependencies(directDependencyMetadata ->
                    directDependencyMetadata.add(dependency)
                )
            );
        }
    }
    public String addDependency(String dependency) {
        project.getDependencies().getComponents().withModule("net.minecraft:minecraft-merged-project-root", MinecraftRule.class,
                conf -> conf.setParams(dependency)
        );
        return dependency;
    }

    public ProjectDependency addDependency(ProjectDependency dependency) {
        String projectPath = dependency.getDependencyProject().getPath();
        String module = "io.github.opencubicchunks.stirrin.__fake_project_dep__:" + projectPath.replaceAll(":", "__");

        for (Configuration configuration : project.getConfigurations()) {
            DependencySubstitutions substitution = configuration.getResolutionStrategy().getDependencySubstitution();
            substitution.substitute(substitution.module(module)).using(
                substitution.variant(substitution.project(projectPath), details ->
                    details.attributes(attrContainer ->
                        attrContainer.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR))
                    )
                )
            );
        }
        project.getDependencies().getComponents().withModule("net.minecraft:minecraft-merged-project-root", MinecraftRule.class,
                conf -> conf.setParams(module + ":1.0")
        );
        return dependency;
    }
}