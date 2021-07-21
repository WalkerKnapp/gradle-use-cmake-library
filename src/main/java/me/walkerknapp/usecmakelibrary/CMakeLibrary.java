package me.walkerknapp.usecmakelibrary;

import me.walkerknapp.cfi.CFIQuery;
import me.walkerknapp.cfi.CMakeInstance;
import me.walkerknapp.cfi.CMakeProject;
import me.walkerknapp.cfi.structs.*;
import me.walkerknapp.usecmakelibrary.tasks.CMakeBuildTask;
import me.walkerknapp.usecmakelibrary.tasks.CMakeInstallTask;
import me.walkerknapp.usecmakelibrary.util.CMakeGenerator;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.artifacts.ArtifactAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.provider.AbstractMinimalProvider;
import org.gradle.api.internal.provider.CollectionProviderInternal;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.internal.provider.ValueSanitizer;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.DisplayName;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.internal.DefaultCppPlatform;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.nativeplatform.internal.toolchains.DefaultToolChainSelector;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.language.plugins.NativeBasePlugin;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.nativeplatform.*;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.gradle.language.cpp.CppBinary.*;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

public class CMakeLibrary implements Plugin<Project> {
    private final ImmutableAttributesFactory attributesFactory;
    private final ToolChainSelector toolChainSelector;

    @Inject
    public CMakeLibrary(ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory) {
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
    }

    public static String createDimensionSuffix(Named dimensionValue, Collection<?> multivalueProperty) {
        return createDimensionSuffix(dimensionValue.getName(), multivalueProperty);
    }

    public static String createDimensionSuffix(String dimensionValue, Collection<?> multivalueProperty) {
        if (isDimensionVisible(multivalueProperty)) {
            return StringUtils.capitalize(dimensionValue.toLowerCase());
        }
        return "";
    }

    private static boolean isDimensionVisible(Collection<?> multivalueProperty) {
        return multivalueProperty.size() > 1;
    }

    private static Set<OperatingSystemFamily> targetMachinesToOperatingSystems(Collection<TargetMachine> targetMachines) {
        return targetMachines.stream().map(TargetMachine::getOperatingSystemFamily).collect(Collectors.toSet());
    }

    private static Set<MachineArchitecture> targetMachinesToArchitectures(Collection<TargetMachine> targetMachines) {
        return targetMachines.stream().map(TargetMachine::getArchitecture).collect(Collectors.toSet());
    }

    private static void addCommonAttributes(BuildType buildType, TargetMachine targetMachine, AttributeContainer runtimeAttributes) {
        runtimeAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
        runtimeAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
        runtimeAttributes.attribute(ARCHITECTURE_ATTRIBUTE, targetMachine.getArchitecture());
        runtimeAttributes.attribute(OPERATING_SYSTEM_ATTRIBUTE, targetMachine.getOperatingSystemFamily());
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(NativeBasePlugin.class);
        project.getPluginManager().apply(StandardToolChainsPlugin.class);

        CMakeExtension cMakeExtension = project.getExtensions().create("cmake", CMakeExtension.class, project.getObjects());

        var objectFactory = project.getObjects();

        project.afterEvaluate(p -> {
            CMakeProject cMakeProject = new CMakeProject(project.file(".").toPath());

            // Allocate a space to setup a build for each of our target machines
            for (TargetMachine targetMachine : cMakeExtension.getTargetMachines().get()) {

                // Check if this target machine has a toolchain available
                ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, new DefaultCppPlatform(targetMachine));
                if(!result.getPlatformToolProvider().isAvailable()) {
                    continue;
                }

                // Start with the release build, any other builds will be generated once we can enumerate them
                Path initialBuildPath = p.getLayout().getBuildDirectory().get().getAsFile().toPath()
                        .resolve("cmake").resolve("release-" + targetMachine.getOperatingSystemFamily().getName() + "-" + targetMachine.getArchitecture().getName());
                try {
                    Files.createDirectories(initialBuildPath);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                CMakeInstance initialInstance = new CMakeInstance(cMakeProject, initialBuildPath);

                // This future will spin until the next time the instance is generated
                CompletableFuture<CodeModel> codeModelFuture = initialInstance.queueRequest(CFIQuery.CODE_MODEL);

                CMakeGenerator.generateCmakeFiles(project, initialBuildPath, "RELEASE",
                        ((DefaultCppPlatform) result.getTargetPlatform()).getNativePlatform(),
                        result.getToolChain());

                // This future will now complete when the generation is finished
                CodeModel codeModel = codeModelFuture.join();

                // TODO: Okay, on single-configuration generators (makefile generators, etc), codeModel.configurations
                //  will *not* have all the possible configurations, it only has the current configurations.
                //  I am assuming here that most projects will have a debug and release config until I can figure out
                //  another way to determine the possible configurations for a project.
                for (BuildType buildType : BuildType.DEFAULT_BUILD_TYPES) {
                    CMakeInstance configInstance;
                    boolean needsRegen;
                    Path configBuildPath;
                    CodeModel.Configuration configuration =  null;

                    // TODO: No matter what the build type we're going to present to gradle is,
                    //  we're going to have cmake build a release binary. This is because cmake
                    //  treats "debug" binaries in a way that is unsafe for the gradle "debug"
                    //  linker to consume. Gradle only ever links against the release version
                    //  of the C Run-time Library, but cmake debug binaries need to be linked
                    //  against the release C Run-time Library always.
                    //  See https://stackoverflow.com/a/42801504.
                    String buildTypeString = "RELEASE";
                    // String buildTypeString = buildType.getName();

                    if (buildType == BuildType.RELEASE) {
                        configInstance = initialInstance;
                        needsRegen = false;
                        configBuildPath = initialBuildPath;
                        configuration = codeModel.configurations.stream()
                                .filter(c -> c.name.equalsIgnoreCase(buildType.getName()))
                                .findAny()
                                .orElseThrow(() -> new IllegalStateException("Configuration for initial release generator run could not be found."));
                    } else {
                        needsRegen = true;
                        configBuildPath = p.getLayout().getBuildDirectory().get().getAsFile().toPath()
                                .resolve("cmake").resolve(buildType.getName() + "-" + targetMachine.getOperatingSystemFamily().getName() + "-" + targetMachine.getArchitecture().getName());
                        configInstance = new CMakeInstance(cMakeProject, configBuildPath);
                    }

                    if (needsRegen) {
                        try {
                            Files.createDirectories(configBuildPath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        // This future will spin until the next time the instance is generated
                        CompletableFuture<CodeModel> configCodeModelFuture = configInstance.queueRequest(CFIQuery.CODE_MODEL);

                        CMakeGenerator.generateCmakeFiles(project, configBuildPath, buildTypeString,
                                ((DefaultCppPlatform) result.getTargetPlatform()).getNativePlatform(),
                                result.getToolChain());

                        CodeModel instanceCodeModel = configCodeModelFuture.join();

                        configuration = instanceCodeModel.configurations.stream()
                                .filter(c -> c.name.equalsIgnoreCase(buildTypeString))
                                .findAny()
                                .orElseThrow(() -> new IllegalStateException("Configuration for configuration " + buildTypeString + " generator run could not be found."));
                    }

                    // Now, we can start to generate the model to expose to gradle based on this information
                    List<String> variantNameToken = new ArrayList<>();
                    variantNameToken.add(buildType.getName());
                    variantNameToken.add(createDimensionSuffix(targetMachine.getOperatingSystemFamily(), targetMachinesToOperatingSystems(cMakeExtension.getTargetMachines().get())));
                    variantNameToken.add(createDimensionSuffix(targetMachine.getArchitecture(), targetMachinesToArchitectures(cMakeExtension.getTargetMachines().get())));

                    String variantName = StringUtils.uncapitalize(String.join("", variantNameToken));

                    TaskProvider<CMakeBuildTask> buildTask = project.getTasks().register("cmakeBuild" + StringUtils.capitalize(variantName), CMakeBuildTask.class, task -> {
                        task.setBuildType(buildTypeString);
                        task.getCmakeFiles().set(project.file(configBuildPath));
                    });

                    TaskProvider<CMakeInstallTask> installTask = project.getTasks().register("cmakeInstall" + StringUtils.capitalize(variantName), CMakeInstallTask.class, task -> {
                        task.setBuildType(buildTypeString);
                        task.getCmakeFiles().set(project.file(configBuildPath));
                        task.dependsOn(buildTask);
                    });

                    // TODO: Right now, this will add *all* installable targets from this config
                    //  Is this right? Should there be more granularity? How could that be implemented?
                    Collection<Target> installableTargets = configuration.targets.stream()
                            .map(t -> configInstance.readReplyObject(Target.class, t.jsonFile))
                            .map(CompletableFuture::join)
                            .filter(t -> t.install != null)
                            .collect(Collectors.toList());

                    // Collect shared libraries
                    Collection<Target> sharedLibraryTargets = installableTargets.stream()
                            .filter(t -> t.type.equals("SHARED_LIBRARY"))
                            .collect(Collectors.toList());

                    // Collect static libraries
                    Collection<Target> staticLibraryTargets = installableTargets.stream()
                            .filter(t -> t.type.equals("STATIC_LIBRARY"))
                            .collect(Collectors.toList());

                    if (!sharedLibraryTargets.isEmpty()) {
                        // Create variants for shared libraries
                        Configuration linkElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "SharedLinkElements");
                        linkElements.setCanBeResolved(false);
                        linkElements.setCanBeConsumed(true);
                        linkElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_LINK));
                        addCommonAttributes(buildType, targetMachine, linkElements.getAttributes());
                        linkElements.getAttributes().attribute(LINKAGE_ATTRIBUTE, Linkage.SHARED);

                        Configuration runtimeElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "SharedRuntimeElements");
                        runtimeElements.setCanBeResolved(false);
                        runtimeElements.setCanBeConsumed(true);
                        runtimeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME));
                        addCommonAttributes(buildType, targetMachine, runtimeElements.getAttributes());
                        runtimeElements.getAttributes().attribute(LINKAGE_ATTRIBUTE, Linkage.SHARED);

                        for (Target t : sharedLibraryTargets) {
                            for (Target.Artifacts artifact : t.artifacts) {
                                if (artifact.path.endsWith(".dll") || artifact.path.endsWith(".so")) {
                                    runtimeElements.getOutgoing().artifact(configBuildPath.resolve(artifact.path).toFile(), arti -> arti.builtBy(buildTask));
                                } else if (artifact.path.endsWith(".lib") || artifact.path.endsWith(".a")) {
                                    linkElements.getOutgoing().artifact(configBuildPath.resolve(artifact.path).toFile(), arti -> arti.builtBy(buildTask));
                                } else {
                                    System.out.println("Ignoring artifact exported by target " + t.name + " (" + t.id + "): " + artifact.path);
                                }
                            }
                        }
                    }

                    if (!staticLibraryTargets.isEmpty()) {
                        // Create variants for static libraries
                        Configuration linkElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "StaticLinkElements");
                        linkElements.setCanBeResolved(false);
                        linkElements.setCanBeConsumed(true);
                        linkElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_LINK));
                        addCommonAttributes(buildType, targetMachine, linkElements.getAttributes());
                        linkElements.getAttributes().attribute(LINKAGE_ATTRIBUTE, Linkage.STATIC);

                        for (Target t : staticLibraryTargets) {
                            for (Target.Artifacts artifact : t.artifacts) {
                                if (artifact.path.endsWith(".lib") || artifact.path.endsWith(".a")) {
                                    linkElements.getOutgoing().artifact(configBuildPath.resolve(artifact.path).toFile(), arti -> arti.builtBy(buildTask));
                                } else {
                                    System.out.println("Ignoring artifact exported by target " + t.name + " (" + t.id + "): " + artifact.path);
                                }
                            }
                        }
                    }

                    Configuration includeElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "ApiElements");
                    includeElements.setCanBeResolved(false);
                    includeElements.setCanBeConsumed(true);
                    includeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.C_PLUS_PLUS_API));
                    addCommonAttributes(buildType, targetMachine, includeElements.getAttributes());
                    includeElements.getAttributes().attribute(ArtifactAttributes.ARTIFACT_FORMAT, ArtifactTypeDefinition.DIRECTORY_TYPE);
                    includeElements.getOutgoing().artifact(installTask.flatMap(CMakeInstallTask::getIncludeDirectory), arti -> arti.builtBy(installTask));
                }
            }
        });
    }
}
