package me.walkerknapp.usecmakelibrary.tasks;

import me.walkerknapp.usecmakelibrary.util.VisualCppUtil;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCppToolChain;
import org.gradle.nativeplatform.toolchain.internal.tools.CommandLineToolSearchResult;
import org.gradle.platform.base.ToolChain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@CacheableTask
public class CMakeGenerateTask extends DefaultTask {
    private String buildType;
    private final Property<NativePlatform> targetPlatform;
    private final Property<NativeToolChain> toolChain;

    public CMakeGenerateTask() {
        this.targetPlatform = getProject().getObjects().property(NativePlatform.class);
        this.toolChain = getProject().getObjects().property(NativeToolChain.class);
    }

    @TaskAction
    public void generateCmakeFiles() throws IOException {
        String cmakeExecutable = System.getenv().getOrDefault("CMAKE_EXECUTABLE", "cmake");

        Files.deleteIfExists(getProject().getRootDir().toPath().resolve("CMakeCache.txt"));

        NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) toolChain.get();
        NativePlatformInternal nativePlatform = (NativePlatformInternal) targetPlatform.get();
        PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

        System.out.println("Using toolchain " + toolChain.get().getDisplayName());

        if (toolChain.get() instanceof VisualCppToolChain) {
            // TODO: We could probably do something smarter than this
            System.out.println("Toolchain is Visual Studio, trying to generate with MSVC...");

            String generatorString = VisualCppUtil.getCMakeGeneratorString((VisualCppToolChain) toolChain.get());
            System.out.println("Using generator " + generatorString);
            System.out.println("Using arch " + VisualCppUtil.getVisualStudioArchString(nativePlatform.getArchitecture()));

            getProject().exec(execSpec -> {
                execSpec.setWorkingDir(getOutputDirectory());
                execSpec.commandLine(cmakeExecutable,
                        "-G", generatorString,
                        "-A", VisualCppUtil.getVisualStudioArchString(nativePlatform.getArchitecture()),
                        "-DCMAKE_BUILD_TYPE=" + capitalize(getBuildType()),
                        "--no-warn-unused-cli", getProject().getRootDir().getAbsolutePath());
            });
        } else {
            System.out.println("Toolchain is non-IDE, trying to create a makefile...");

            CommandLineToolSearchResult cCompilerRes = platformToolProvider.locateTool(ToolType.C_COMPILER);
            CommandLineToolSearchResult cppCompilerRes = platformToolProvider.locateTool(ToolType.CPP_COMPILER);

            if (!cCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find C compiler for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }
            if (!cppCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find C++ compiler for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }

            System.out.println("Found c compiler: " + cCompilerRes.getTool().getAbsolutePath());
            System.out.println("Found c++ compiler: " + cppCompilerRes.getTool().getAbsolutePath());

            getProject().exec(execSpec -> execSpec.commandLine(cmakeExecutable,
                    "-DCMAKE_C_COMPILER=" + cCompilerRes.getTool().getAbsolutePath(),
                    "-DCMAKE_CXX_COMPILER=" + cppCompilerRes.getTool().getAbsolutePath(),
                    "-DCMAKE_BUILD_TYPE=" + capitalize(getBuildType()),
                    "--no-warn-unused-cli", getProject().getRootDir().getAbsolutePath()));
        }


    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getCMakeLists() {
        return getProject().fileTree(getProject().getRootDir(), it -> it.include("**/CMakeLists.txt"));
    }

    @Input
    public String getBuildType() {
        return buildType;
    }

    public void setBuildType(String buildType) {
        this.buildType = buildType;
    }

    @Internal
    public Property<NativeToolChain> getToolChain() {
        return toolChain;
    }

    @Nested
    public Property<NativePlatform> getTargetPlatform() {
        return this.targetPlatform;
    }

    @OutputDirectory
    public File getOutputDirectory() {
        return getTemporaryDir();
    }
}
