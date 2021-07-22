package me.walkerknapp.usecmakelibrary;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.nativeplatform.TargetMachine;

import java.util.List;

public class CMakeExtension {
    private SetProperty<TargetMachine> targetMachines;

    private Action<List<String>> arguments;

    public CMakeExtension(ObjectFactory objectFactory) {
        this.targetMachines = objectFactory.setProperty(TargetMachine.class);

        this.arguments = strings -> { /* no-op */ };
    }

    public SetProperty<TargetMachine> getTargetMachines() {
         return this.targetMachines;
    }

    public Action<List<String>> getArguments() {
        return arguments;
    }

    public void setArguments(Action<List<String>> arguments) {
        this.arguments = arguments;
    }
}
