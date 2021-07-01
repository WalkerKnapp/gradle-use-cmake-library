package me.walkerknapp.usecmakelibrary;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.nativeplatform.TargetMachine;

public class CMakeExtension {
    private SetProperty<TargetMachine> targetMachines;

    public CMakeExtension(ObjectFactory objectFactory) {
        this.targetMachines = objectFactory.setProperty(TargetMachine.class);
    }

    public SetProperty<TargetMachine> getTargetMachines() {
         return this.targetMachines;
    }
}
