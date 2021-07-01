package me.walkerknapp.usecmakelibrary;

import me.walkerknapp.usecmakelibrary.tasks.CMakeBuildTask;
import me.walkerknapp.usecmakelibrary.tasks.CMakeGenerateTask;
import me.walkerknapp.usecmakelibrary.tasks.CMakeInstallTask;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.artifacts.ArtifactAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.internal.DefaultCppPlatform;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.nativeplatform.internal.toolchains.DefaultToolChainSelector;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.language.plugins.NativeBasePlugin;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.nativeplatform.*;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;

import javax.inject.Inject;
import java.util.*;
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
            for (BuildType buildType : BuildType.DEFAULT_BUILD_TYPES) {
                // TODO: Pull this out of the cmake build?
                Linkage linkage = Linkage.STATIC;
                List<Linkage> linkages = Arrays.asList(Linkage.values());
                for (TargetMachine targetMachine : cMakeExtension.getTargetMachines().get()) {
                    // From org.gradle.language.nativeplatform.internal.Dimensions
                    Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
                    Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

                    List<String> variantNameToken = new ArrayList<>();
                    variantNameToken.add(buildType.getName());
                    variantNameToken.add(createDimensionSuffix(linkage, linkages));
                    variantNameToken.add(createDimensionSuffix(targetMachine.getOperatingSystemFamily(), targetMachinesToOperatingSystems(cMakeExtension.getTargetMachines().get())));
                    variantNameToken.add(createDimensionSuffix(targetMachine.getArchitecture(), targetMachinesToArchitectures(cMakeExtension.getTargetMachines().get())));

                    String variantName = StringUtils.uncapitalize(String.join("", variantNameToken));

                    AttributeContainer runtimeAttributes = attributesFactory.mutable();
                    runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
                    addCommonAttributes(buildType, targetMachine, runtimeAttributes);
                    runtimeAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                    DefaultUsageContext runtimeUsageContext = new DefaultUsageContext(variantName + "Runtime", runtimeAttributes);

                    AttributeContainer linkAttributes = attributesFactory.mutable();
                    linkAttributes.attribute(Usage.USAGE_ATTRIBUTE, linkUsage);
                    addCommonAttributes(buildType, targetMachine, linkAttributes);
                    linkAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                    DefaultUsageContext linkUsageContext = new DefaultUsageContext(variantName + "Link", linkAttributes);

                    ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, new DefaultCppPlatform(targetMachine));

                    if(!result.getPlatformToolProvider().isAvailable()) {
                        continue;
                    }

                    TaskProvider<CMakeGenerateTask> generateTask = project.getTasks().register("cmakeGenerate" + StringUtils.capitalize(variantName), CMakeGenerateTask.class, task -> {
                        task.setBuildType(buildType.getName());
                        task.getTargetPlatform().set(((DefaultCppPlatform) result.getTargetPlatform()).getNativePlatform());
                        task.getToolChain().set(result.getToolChain());
                    });

                    TaskProvider<CMakeBuildTask> buildTask = project.getTasks().register("cmakeBuild" + StringUtils.capitalize(variantName), CMakeBuildTask.class, task -> {
                        task.setBuildType(buildType.getName());
                        task.getCmakeFiles().set(project.getLayout().dir(generateTask.map(CMakeGenerateTask::getOutputDirectory)));
                    });

                    TaskProvider<CMakeInstallTask> installTask = project.getTasks().register("cmakeInstall" + StringUtils.capitalize(variantName), CMakeInstallTask.class, task -> {
                        task.getCmakeFiles().set(project.getLayout().dir(generateTask.map(CMakeGenerateTask::getOutputDirectory)));
                        task.dependsOn(buildTask);
                    });

                    Configuration linkElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "LinkElements");
                    linkElements.setCanBeResolved(false);
                    linkElements.setCanBeConsumed(true);
                    for (Attribute<?> attribute : linkAttributes.keySet()) {
                        linkElements.getAttributes().attribute((Attribute<Object>) attribute, linkAttributes.getAttribute(attribute));
                    }
                    linkElements.getOutgoing().artifact(installTask.flatMap(CMakeInstallTask::getLibDirectory), arti -> arti.builtBy(installTask));

                    Configuration runtimeElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "RuntimeElements");
                    runtimeElements.setCanBeResolved(false);
                    runtimeElements.setCanBeConsumed(true);
                    for (Attribute<?> attribute : runtimeAttributes.keySet()) {
                        runtimeAttributes.getAttributes().attribute((Attribute<Object>) attribute, runtimeAttributes.getAttribute(attribute));
                    }
                    runtimeElements.getOutgoing().artifact(installTask.flatMap(CMakeInstallTask::getBinDirectory), arti -> arti.builtBy(installTask));

                    Configuration includeElements = project.getConfigurations().create(StringUtils.uncapitalize(variantName) + "ApiElements");
                    includeElements.setCanBeResolved(false);
                    includeElements.setCanBeConsumed(true);
                    includeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.C_PLUS_PLUS_API));
                    includeElements.getAttributes().attribute(ArtifactAttributes.ARTIFACT_FORMAT, ArtifactTypeDefinition.DIRECTORY_TYPE);
                    includeElements.getOutgoing().artifact(installTask.flatMap(CMakeInstallTask::getIncludeDirectory), arti -> arti.builtBy(installTask));
                }
            }
        });
    }
}
