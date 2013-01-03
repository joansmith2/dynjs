package org.dynjs.exception;

public class ModuleLoadException extends DynJSException {

    private static final long serialVersionUID = -1402931758319396373L;

    public ModuleLoadException(String moduleName, Throwable cause) {
        super(cause);
        this.moduleName = moduleName;
    }

    public ModuleLoadException(String moduleName, String message) {
        super(message);
        this.moduleName = moduleName;
    }

    public String getMessage() {
        return "Unable to load module '" + this.moduleName + "': " + super.getMessage();
    }

    public String getModuleName() {
        return this.moduleName;
    }

    private String moduleName;
}
