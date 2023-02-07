package io.github.opencubicchunks.stirrin;

import io.github.opencubicchunks.stirrin.StirrinTransform.Parameters;
import io.github.opencubicchunks.stirrin.util.Pair;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.*;

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

        Set<File> sourceSetDirectories = new HashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            sourceSetDirectories.addAll(sourceSet.getJava().getSrcDirs());
        }

        this.parameters.setConfigs(mixinConfigFiles);
        Map<File, String> files = new HashMap<>();
        mixinClassFiles.forEach(pair -> files.put(pair.l(), pair.r()));
        this.parameters.setMixinFiles(files);
        this.parameters.setSourceSetDirectories(sourceSetDirectories);
    }

    public void setDebug(boolean value) {
        this.parameters.setDebug(value ? System.nanoTime() : 0);
    }

    public void setAdditionalSourceSets(Set<File> additionalSourceSets) {
        this.parameters.setAdditionalSourceSets(additionalSourceSets);
    }
}