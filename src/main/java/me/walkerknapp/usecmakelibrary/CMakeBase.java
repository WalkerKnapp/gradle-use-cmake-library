package me.walkerknapp.usecmakelibrary;

import groovy.lang.Closure;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class CMakeBase implements Plugin<Project> {
    @Override
    public void apply(Project target) {
        if (System.getProperty("CMAKE_TOOLCHAINS_CONFIG_SCRIPT") != null) {
           target.apply(oca -> {
               oca.from(System.getProperty("CMAKE_TOOLCHAINS_CONFIG_SCRIPT"));
           });
        }

        target.getPlugins().apply(CMakeLibrary.class);
    }
}
