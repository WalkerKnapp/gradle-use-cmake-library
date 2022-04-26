# gradle-use-cmake-library
A gradle plugin to use any installable CMake library in the native plugins ecosystem.

More fleshed out documentation is planned, but for now, here is a concise description of usage of this plugin adapted from https://github.com/WalkerKnapp/gradle-use-cmake-library/issues/1

Unfortunately the process is pretty in-depth because the Gradle native plugins themselves are not very fleshed out. 

### Adding Gradle to an existing CMake project

If you just want to "gradleify" a cmake project to make it available to use for Gradle projects, the process isn't too complicated. You'll want to create a `gradle.build` file in the root of your cmake project. Start it with
```groovy
plugins {
	id "me.walkerknapp.use-cmake-library"
}

cmake.targetMachines.set([
			machines.windows.x86, machines.windows.x86_64,
			machines.macOS.x86_64,
			machines.linux.x86, machines.linux.x86_64,
			machines.os("android").architecture("armv7a"),
			machines.os("android").architecture("arm64-v8a"),
			machines.os("android").x86,
			machines.os("android").x86_64])
```

This will import the plugin and give it a list of machines to target. By default, Gradle will only be able to compile for one target, which can be fine, but to specify additional compilers, I usually add:

```groovy
apply {
    from('toolchains.gradle.kts')
}
```

and then make a file like https://github.com/WalkerKnapp/rapidopus/blob/main/toolchains.gradle.kts detailing various compliers to use.

Also to note, part of Gradle's underdeveloped native plugins is a lack of cross-compilation support. There has been a PR sitting for more than 2 years that solves this (gradle/gradle#10024), but until it is looked at again, I use a Gradle fork that has these changes merged: https://github.com/WalkerKnapp/gradle/releases/tag/v7.2cc. You can use it by running:
```
gradlew wrapper --gradle-distribution-url=https://github.com/WalkerKnapp/gradle/releases/download/v7.2cc/gradle-7.2-bin.zip
```

After you have your Gradle project set up, you can depend on it from Gradle native applications, build it with Gradle, etc.

### Injecting Gradle into a CMake-Based Source Dependency

An entire project that uses this plugin more practically is https://github.com/WalkerKnapp/rapidopus, but this is a little bit more complicated than needed because instead of just "gradleifying" an existing cmake project, it injects this plugin into a source dependency (a cmake project downloaded from GitHub on the fly).

The gist of the process is that you first make a plugin that can be injected into a source dependency: https://github.com/WalkerKnapp/rapidopus/tree/main/plugins (This is the part that relies on this project)

Next you create a source dependency on the repository that hosts the cmake library you want to use. You can inject the plugin you previously made into this dependency. https://github.com/WalkerKnapp/rapidopus/blob/main/settings.gradle

Then you can depend on the cmake project from the source dependency like a normal gradle dependency: https://github.com/WalkerKnapp/rapidopus/blob/93148b402f628a03a5912b514e6f21a0e74fadfa/rapidopus-natives/build.gradle.kts#L80
