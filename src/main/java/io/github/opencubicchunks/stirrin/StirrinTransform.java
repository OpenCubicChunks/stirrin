package io.github.opencubicchunks.stirrin;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import static io.github.opencubicchunks.stirrin.Stirrin.LOGGER;

public abstract class StirrinTransform implements TransformAction<StirrinTransform.Parameters> {
    interface Parameters extends TransformParameters {
        @Input String getAcceptedJars();
        void setAcceptedJars(String acceptedJars);

        @InputFile Set<String> getConfigs();
        void setConfigs(Set<String> configs);

        @Input Map<String, Set<String>> getInterfacesByMixinClass();
        void setInterfacesByMixinClass(Map<String, Set<String>> interfacesByMixinClass);

        @Input long getDebug();
        void setDebug(long l);
    }

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        String fileName = getInputArtifact().get().getAsFile().getName();

        Pattern acceptedJars = Pattern.compile(getParameters().getAcceptedJars());

        if (acceptedJars.matcher(fileName).matches()) {
            LOGGER.warn(String.format("found %s", getInputArtifact().get().getAsFile()));

            String fileNameNoExt = fileName.substring(0, fileName.lastIndexOf("."));
            String outputFileName = fileNameNoExt + "-mixinInterfaces.jar";

            StirrinTransformer.transformMinecraftJar(getParameters().getInterfacesByMixinClass(), getInputArtifact().get().getAsFile(),
                    outputs.file(outputFileName));

            LOGGER.warn(String.format("transformed %s", outputFileName));
            return;
        } else {
            LOGGER.info(String.format("Rejected jar %s", fileName));
        }
        outputs.file(getInputArtifact());
    }
}