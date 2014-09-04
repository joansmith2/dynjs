package org.dynjs.runtime.builtins.types.error;

import org.dynjs.runtime.ExecutionContext;
import org.dynjs.runtime.JSObject;
import org.dynjs.runtime.Reference;
import org.dynjs.runtime.Types;

public class StackElement {

    public StackElement(String debugContext, ExecutionContext context) {
        this.debugContext = debugContext;
        this.context = context;

        this.contextString = "<global>";
        int dotLoc = this.debugContext.indexOf(".");
        if (dotLoc > 0) {
            this.contextString = this.debugContext.substring(0, dotLoc);
            this.function = this.debugContext.substring(dotLoc + 1);
        } else {
            this.function = this.debugContext;
        }
        this.lineNumber = context.getLineNumber();
    }

    public StackTraceElement toStackTraceElement() {
        return new StackTraceElement(contextString, function, context.getFileName(), lineNumber);
    }

    public String toString() {
        return this.debugContext + " (" + context.getFileName() + ":" + lineNumber + ")";
    }



    public String getFileName() {
        return context.getFileName();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getDebugContext() {
        return debugContext;
    }

    public Object getFunction() {
        return context.getFunctionReference();
    }

    public String getFunctionName() {
        return function;
    }

    public Object getThis() {
        return context.getThisBinding();
    }

    public Object getTypeName() {
        Object thisBinding = getThis();
        if (thisBinding instanceof JSObject) {
            JSObject ctor = (JSObject) ((JSObject) thisBinding).get(null, "constructor");
            if (ctor != null) {
                if (ctor.get(null, "name") != Types.UNDEFINED) {
                    return ctor.get(null, "name");
                } else {
                    return ctor.getClassName();
                }
            }
        }
        return null;
    }

    public Object getMethodName() {
        if (getFunction() != null) {
            Reference functionBinding = (Reference) getFunction();
            return functionBinding.getReferencedName();
        }
        return null;
    }

    // TODO: Add support for all of the following method stubs
    public int getColumnNumber() {
        return 0;
    }

    public StackElement getEvalOrigin() {
        return null;
    }

    public boolean isTopLevel() {
        return false;
    }

    public boolean isEval() {
        return false;
    }

    public boolean isNative() {
        return false;
    }

    public boolean isConstructor() {
        return false;
    }

    private final String debugContext;
    private final String function;
    private final int lineNumber;
    private final ExecutionContext context;
    private String contextString;
}