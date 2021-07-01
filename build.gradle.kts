plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "0.14.0"
}

group = "me.walkerknapp"
version = "0.0.1"

gradlePlugin {
    plugins {
        create("useCMakeLibrary") {
            id = "me.walkerknapp.use-cmake-library"
            displayName = "Use CMake Library"
            description = "A gradle plugin to use any installable CMake library in the native plugins ecosystem."
            implementationClass = "me.walkerknapp.usecmakelibrary.CMakeLibrary"
        }
    }
}

pluginBundle {
    website = "https://github.com/WalkerKnapp/gradle-use-cmake-library"
    vcsUrl = "https://github.com/WalkerKnapp/gradle-use-cmake-library"
    tags = listOf("native", "cmake")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.commons:commons-lang3:3+")
}

