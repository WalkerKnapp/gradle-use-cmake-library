package me.walkerknapp.usecmakelibrary.util;

import me.walkerknapp.usecmakelibrary.CMakeExtension;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.language.base.internal.compile.VersionAwareCompiler;
import org.gradle.nativeplatform.internal.LinkerSpec;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.*;
import org.gradle.nativeplatform.toolchain.internal.compilespec.CCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.compilespec.CppCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCppToolChain;
import org.gradle.nativeplatform.toolchain.internal.tools.CommandLineToolSearchResult;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CMakeGenerator {

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public static void generateCmakeFiles(Project project, CMakeExtension extension, Path outputDirectory, String buildType, NativePlatform targetPlatform, NativeToolChain toolChain) {
        String cmakeExecutable = System.getenv().getOrDefault("CMAKE_EXECUTABLE", "cmake");

        NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) toolChain;
        NativePlatformInternal nativePlatform = (NativePlatformInternal) targetPlatform;
        PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

        System.out.println("Using toolchain " + toolChain.getDisplayName());

        if (toolChain instanceof VisualCppToolChain) {
            // TODO: We could probably do something smarter than this
            System.out.println("Toolchain is Visual Studio, trying to generate with MSVC...");

            String generatorString = VisualCppUtil.getCMakeGeneratorString((VisualCppToolChain) toolChain);
            System.out.println("Using generator " + generatorString);
            System.out.println("Using arch " + VisualCppUtil.getVisualStudioArchString(nativePlatform.getArchitecture()));

            project.exec(execSpec -> {
                execSpec.setWorkingDir(outputDirectory);
                execSpec.commandLine(cmakeExecutable,
                        "-G", generatorString,
                        "-A", VisualCppUtil.getVisualStudioArchString(nativePlatform.getArchitecture()),
                        "-DMSVC_RUNTIME_LIBRARY=\"\"",
                        "-DCMAKE_BUILD_TYPE=" + capitalize(buildType),
                        "--no-warn-unused-cli", project.getRootDir().getAbsolutePath());
            });
        } else {
            System.out.println("Toolchain is non-IDE, trying to create a makefile...");

            CommandLineToolSearchResult cCompilerRes = platformToolProvider.locateTool(ToolType.C_COMPILER);
            CommandLineToolSearchResult cppCompilerRes = platformToolProvider.locateTool(ToolType.CPP_COMPILER);
            CommandLineToolSearchResult arCompilerRes = platformToolProvider.locateTool(ToolType.STATIC_LIB_ARCHIVER);
            CommandLineToolSearchResult objcopyCompilerRes = platformToolProvider.locateTool(ToolType.SYMBOL_EXTRACTOR);
            CommandLineToolSearchResult stripCompilerRes = platformToolProvider.locateTool(ToolType.STRIPPER);

            if (!cCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find C compiler for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }
            if (!cppCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find C++ compiler for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }
            if (!arCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find AR for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }
            if (!objcopyCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find objcopy for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }
            if (!stripCompilerRes.isAvailable()) {
                throw new AssertionError("Could not find strip for platform " + nativePlatform.getDisplayName()
                        + " using toolchain " + nativeToolChain.getDisplayName());
            }

            System.out.println("Found c compiler: " + cCompilerRes.getTool().getAbsolutePath());
            System.out.println("Found c++ compiler: " + cppCompilerRes.getTool().getAbsolutePath());
            System.out.println("Found ar tool: " + arCompilerRes.getTool().getAbsolutePath());
            System.out.println("Found objcopy tool: " + objcopyCompilerRes.getTool().getAbsolutePath());
            System.out.println("Found strip tool: " + stripCompilerRes.getTool().getAbsolutePath());

            // Here's the hard part, we need to locate a compatible make
            String makeExecutable = System.getenv().getOrDefault("MAKE_EXECUTABLE", "make");

            String makefileGenerator;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                project.exec(execSpec -> {
                    execSpec.commandLine(makeExecutable, "-v");
                    execSpec.setStandardOutput(baos);
                });
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(baos.toByteArray())))) {
                    reader.readLine(); // First line specifies version
                    String makeBuildSpec = reader.readLine(); // First line is "Built for arch-os"

                    if (makeBuildSpec.toLowerCase().contains("mingw")) {
                        makefileGenerator = "MinGW Makefiles";
                    } else if (makeBuildSpec.toLowerCase().contains("msys")) {
                        makefileGenerator = "MSYS Makefiles";
                    } else if (makeBuildSpec.toLowerCase().contains("nux") || makeBuildSpec.toLowerCase().contains("nix")) {
                        makefileGenerator = "Unix Makefiles";
                    } else {
                        throw new IllegalStateException("Unknown make specification, \"" + makeBuildSpec + "\" at path \"" + makeExecutable + "\".");
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not execute make at path \"" + makeExecutable + "\".", e);
            }

            // Find arguments we need to extract from our c compiler / cpp compiler / linker
            final AtomicReference<CommandLineToolContext> cppToolContext = new AtomicReference<>();
            final AtomicReference<CommandLineToolContext> cToolContext = new AtomicReference<>();
            final AtomicReference<CommandLineToolContext> linkerToolContext = new AtomicReference<>();
            try {
                Field rawCompiler = VersionAwareCompiler.class.getDeclaredField("compiler");
                rawCompiler.setAccessible(true);
                Field outputCompiler = OutputCleaningCompiler.class.getDeclaredField("compiler");
                outputCompiler.setAccessible(true);

                Object cppCompiler = outputCompiler.get(rawCompiler.get(platformToolProvider.newCompiler(CppCompileSpec.class)));
                Object cCompiler = outputCompiler.get(rawCompiler.get(platformToolProvider.newCompiler(CCompileSpec.class)));
                Object linker = rawCompiler.get(platformToolProvider.newCompiler(LinkerSpec.class));

                Field invocationContext = AbstractCompiler.class.getDeclaredField("invocationContext");
                invocationContext.setAccessible(true);

                cppToolContext.set((CommandLineToolContext) invocationContext.get(cppCompiler));
                cToolContext.set((CommandLineToolContext) invocationContext.get(cCompiler));
                linkerToolContext.set((CommandLineToolContext) invocationContext.get(linker));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException("Could not access AbstractCompiler Internals, ", e);
            }

            // Get CMAKE_SYSTEM_NAME variable
            String cmakeSystemName = null;
            if (targetPlatform.getOperatingSystem().getName().toLowerCase().contains("android")) {
                cmakeSystemName = "Android";
            } else if (targetPlatform.getOperatingSystem().getName().toLowerCase().contains("win")) {
                cmakeSystemName = "Windows";
            } else if (targetPlatform.getOperatingSystem().getName().toLowerCase().contains("nix") ||
            targetPlatform.getOperatingSystem().getName().toLowerCase().contains("nux")) {
                cmakeSystemName = "Linux";
            } else {
                cmakeSystemName = "Darwin";
            }

            ArrayList<String> cli = new ArrayList<>(List.of(cmakeExecutable,
                    "-G", makefileGenerator,
                    "-DCMAKE_SYSTEM_NAME=" + cmakeSystemName,
                    "-DCMAKE_SYSTEM_VERSION=1",
                    "-DCMAKE_MAKE_PROGRAM=" + makeExecutable.replace('\\', '/'),
                    "-DCMAKE_AR=" + arCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_C_COMPILER=" + cCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_C_FLAGS=" + formatToolArgs(cppToolContext.get().getArgAction()).replace('\\', '/'),
                    "-DCMAKE_C_COMPILER_AR=" + arCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_C_ARCHIVE_FINISH=<CMAKE_AR> -s <TARGET>",
                    "-DCMAKE_CXX_COMPILER=" + cppCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_CXX_FLAGS=" + formatToolArgs(cToolContext.get().getArgAction()).replace('\\', '/'),
                    "-DCMAKE_CXX_COMPILER_AR=" + arCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_CXX_ARCHIVE_FINISH=<CMAKE_AR> -s <TARGET>",
                    "-DCMAKE_OBJCOPY=" + objcopyCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_STRIP=" + stripCompilerRes.getTool().getAbsolutePath().replace('\\', '/'),
                    "-DCMAKE_BUILD_TYPE=" + capitalize(buildType)));

            extension.getArguments().execute(cli);

            cli.addAll(List.of("--no-warn-unused-cli",
                    project.getRootDir().getAbsolutePath().replace('\\', '/')));

            project.exec(execSpec -> {
                execSpec.setWorkingDir(outputDirectory);
                execSpec.commandLine(cli);
            });
        }
    }

    private static String formatToolArgs(Action<List<String>> argAction) {
        List<String> args = new ArrayList<>();
        argAction.execute(args);
        return String.join(" ", args);
    }
}
