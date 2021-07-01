package me.walkerknapp.usecmakelibrary.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;

@CacheableTask
public class CMakeBuildTask extends DefaultTask {
    private String buildType;
    private final DirectoryProperty cmakeFiles;

    public CMakeBuildTask() {
        this.cmakeFiles = getProject().getObjects().directoryProperty();
    }

    @TaskAction
    public void buildCmakeProject() {
        String cmakeExecutable = System.getenv().getOrDefault("CMAKE_EXECUTABLE", "cmake");

        getProject().exec(execSpec -> {
            execSpec.setWorkingDir(this.cmakeFiles.getAsFile().get());
            execSpec.commandLine(cmakeExecutable,
                    "--build", this.cmakeFiles.get().getAsFile().getAbsolutePath(),
                    "--config", this.buildType);
        });
    }

    @Input
    public String getBuildType() {
        return buildType;
    }

    public void setBuildType(String buildType) {
        this.buildType = buildType;
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public DirectoryProperty getCmakeFiles() {
        return this.cmakeFiles;
    }
}
