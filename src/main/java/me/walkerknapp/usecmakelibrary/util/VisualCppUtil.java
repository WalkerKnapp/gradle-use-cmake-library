package me.walkerknapp.usecmakelibrary.util;

import org.gradle.nativeplatform.platform.Architecture;
import org.gradle.nativeplatform.platform.internal.ArchitectureInternal;
import org.gradle.nativeplatform.platform.internal.Architectures;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCppInstall;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCppToolChain;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualStudioInstall;

import java.lang.reflect.Field;

public class VisualCppUtil {
    public static String getCMakeGeneratorString(VisualCppToolChain toolChain) {
        try {
            Field f = VisualCppToolChain.class.getDeclaredField("visualStudio");
            f.setAccessible(true);
            VisualStudioInstall visualStudioInstall = (VisualStudioInstall) f.get(toolChain);

            switch (visualStudioInstall.getVersion().getMajor()) {
                case 16: return "Visual Studio 16 2019";
                case 15: return "Visual Studio 15 2017";
                case 14: return "Visual Studio 14 2015";
                case 12: return "Visual Studio 12 2013";
                case 11: return "Visual Studio 11 2012";
                case 10: return "Visual Studio 10 2010";
                case 9: return "Visual Studio 9 2008";
                case 8: return "Visual Studio 8 2005";
                case 7: if (visualStudioInstall.getVersion().getMinor() == 1) {
                    return "Visual Studio 7 .NET 2003";
                } else {
                    return "Visual Studio 7";
                }
                case 6: return "Visual Studio 6";
                default: return "";
            }

        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static Architectures.KnownArchitecture armV8Arch() {
        // WTF
        return new Architectures.KnownArchitecture("arm-v8", "arm64");
    }

    public static String getVisualStudioArchString(Architecture architecture) {
        if (Architectures.X86_64.isAlias(architecture.getName())) {
            return "x64";
        } else if (Architectures.X86.isAlias(architecture.getName())) {
            return "Win32";
        } else if (Architectures.ARM_V7.isAlias(architecture.getName())) {
            return "ARM";
        } else if (armV8Arch().isAlias(architecture.getName())) {
            return "ARM64";
        } else {
            throw new IllegalArgumentException("Cannot compile architecture " + architecture.getDisplayName() + " with MSVC.");
        }
    }
}
