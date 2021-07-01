package me.walkerknapp.usecmakelibrary.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.provider.Providers;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;

import java.io.File;

@CacheableTask
public class CMakeInstallTask extends DefaultTask {
    private final DirectoryProperty cmakeFiles;

    public CMakeInstallTask() {
        this.cmakeFiles = getProject().getObjects().directoryProperty();
    }

    @TaskAction
    public void installCmakeProject() {
        String cmakeExecutable = System.getenv().getOrDefault("CMAKE_EXECUTABLE", "cmake");

        getProject().exec(execSpec -> {
            execSpec.setWorkingDir(getOutputDirectory());
            execSpec.commandLine(cmakeExecutable,
                    "--install", this.cmakeFiles.get().getAsFile().getAbsolutePath(),
                    "--prefix", this.getOutputDirectory().getAbsolutePath());
        });
    }

    @OutputFiles
    public FileCollection getBinFiles() {
        // TODO: This assumes that CMAKE_INSTALL_BINDIR has not been modified
        return filesFromInstallFolder("bin");
    }

    @OutputDirectory
    public Provider<Directory> getBinDirectory() {
        return installFolder("bin");
    }

    @OutputFiles
    public FileCollection getSbinFiles() {
        // TODO: This assumes that CMAKE_INSTALL_SBINDIR has not been modified
        return filesFromInstallFolder("sbin");
    }

    @OutputFiles
    public FileCollection getLibFiles() {
        // TODO: This assumes that CMAKE_INSTALL_LIBDIR has not been modified
        return filesFromInstallFolder("lib");
    }

    @OutputDirectory
    public Provider<Directory> getLibDirectory() {
        return installFolder("lib");
    }

    @OutputFiles
    public FileCollection getIncludeFiles() {
        // TODO: This assumes that CMAKE_INSTALL_INCLUDEDIR has not been modified
        return filesFromInstallFolder("include");
    }

    @OutputDirectory
    public Provider<Directory> getIncludeDirectory() {
        return installFolder("include");
    }

    @OutputFiles
    public FileCollection getSysconfFiles() {
        // TODO: This assumes that CMAKE_INSTALL_SYSCONFDIR has not been modified
        return filesFromInstallFolder("etc");
    }

    @OutputFiles
    public FileCollection getShareStateFiles() {
        // TODO: This assumes that CMAKE_INSTALL_SHARESTATEDIR has not been modified
        return filesFromInstallFolder("com");
    }

    @OutputFiles
    public FileCollection getDataFiles() {
        // TODO: This assumes that CMAKE_INSTALL_DATAROOTDIR has not been modified
        return filesFromInstallFolder("share");
    }

    public FileCollection filesFromInstallFolder(String folder) {
        this.getOutputDirectory().toPath().resolve(folder).toFile().mkdir();
        return getProject().fileTree(this.getOutputDirectory().toPath().resolve(folder).toFile());
    }

    public Provider<Directory> installFolder(String folder) {
        this.getOutputDirectory().toPath().resolve(folder).toFile().mkdir();
        return getProject().getLayout().dir(Providers.of(this.getOutputDirectory().toPath().resolve(folder).toFile()));
    }

    @OutputDirectory
    public File getOutputDirectory() {
        return getTemporaryDir();
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public DirectoryProperty getCmakeFiles() {
        return this.cmakeFiles;
    }
}
