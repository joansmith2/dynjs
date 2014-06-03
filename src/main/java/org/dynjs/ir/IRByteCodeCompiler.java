package org.dynjs.ir;

import me.qmx.jitescript.CodeBlock;
import me.qmx.jitescript.JiteClass;
import me.qmx.jitescript.internal.org.objectweb.asm.ClassReader;
import me.qmx.jitescript.internal.org.objectweb.asm.Opcodes;
import me.qmx.jitescript.internal.org.objectweb.asm.tree.LabelNode;
import me.qmx.jitescript.internal.org.objectweb.asm.util.CheckClassAdapter;
import org.dynjs.codegen.CodeGeneratingVisitor;
import org.dynjs.exception.DynJSException;
import org.dynjs.ir.instructions.Add;
import org.dynjs.ir.instructions.BEQ;
import org.dynjs.ir.instructions.Call;
import org.dynjs.ir.instructions.Copy;
import org.dynjs.ir.instructions.DefineFunction;
import org.dynjs.ir.instructions.Jump;
import org.dynjs.ir.instructions.LT;
import org.dynjs.ir.instructions.ReceiveFunctionParameter;
import org.dynjs.ir.instructions.Return;
import org.dynjs.ir.instructions.Sub;
import org.dynjs.ir.operands.BooleanLiteral;
import org.dynjs.ir.operands.DynamicVariable;
import org.dynjs.ir.operands.IntegerNumber;
import org.dynjs.ir.operands.Label;
import org.dynjs.ir.operands.LocalVariable;
import org.dynjs.ir.operands.TemporaryVariable;
import org.dynjs.ir.operands.Undefined;
import org.dynjs.ir.operands.Variable;
import org.dynjs.ir.representations.BasicBlock;
import org.dynjs.runtime.AbstractFunction;
import org.dynjs.runtime.DynamicClassLoader;
import org.dynjs.runtime.ExecutionContext;
import org.dynjs.runtime.GlobalObject;
import org.dynjs.runtime.JSCallable;
import org.dynjs.runtime.JSFunction;
import org.dynjs.runtime.JSProgram;
import org.dynjs.runtime.LexicalEnvironment;
import org.dynjs.runtime.Types;
import org.dynjs.runtime.VariableValues;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static me.qmx.jitescript.util.CodegenUtils.ci;
import static me.qmx.jitescript.util.CodegenUtils.p;
import static me.qmx.jitescript.util.CodegenUtils.params;
import static me.qmx.jitescript.util.CodegenUtils.sig;

public class IRByteCodeCompiler {
    private final FunctionScope scope;
    private final String fileName;
    private final boolean strict;
    private final List<BasicBlock> blockList;
    private final AtomicInteger methodCounter = new AtomicInteger();
    private static final AtomicInteger compiledFunctionCounter = new AtomicInteger();

    public IRByteCodeCompiler(FunctionScope scope, String fileName, boolean strict) {
        this.scope = scope;
        this.fileName = fileName;
        this.strict = strict;
        this.blockList = scope.prepareForCompilation();
    }

    public JSProgram compile() {
        final int varOffset = 2;
        final List<BasicBlock> blockList = scope.prepareForCompilation();
        Object[] temps = new Object[scope.getTemporaryVariableSize()];
        Object[] vars = new Object[scope.getLocalVariableSize()];
        final HashMap<Label, LabelNode> jumpMap = new HashMap<>();


        System.out.println("VROGRAM:");

        final JiteClass jiteClass = new JiteClass("org/dynjs/gen/" + "MEH");
        jiteClass.defineDefaultConstructor();
        CodeBlock block = new CodeBlock();
        // first pass for gathering labels
        for (BasicBlock bb : blockList) {
            final Label label = bb.getLabel();
            System.out.println("label: " + label);
            final LabelNode labelNode = new LabelNode();
            jumpMap.put(label, labelNode);
        }

        // second pass for emitting
        for (BasicBlock bb : blockList) {
            block.label(jumpMap.get(bb.getLabel()));

            for (Instruction instruction : bb.getInstructions()) {
                System.out.println(instruction);
                emitInstruction(jiteClass, jumpMap, block, instruction);
            }
        }
        block.aconst_null();
        block.areturn();

        jiteClass.defineMethod("execute", Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, sig(Object.class), block);
        final byte[] bytes = jiteClass.toBytes();
        ClassReader reader = new ClassReader(bytes);
        CheckClassAdapter.verify(reader, true, new PrintWriter(System.out));

        final DynamicClassLoader loader = new DynamicClassLoader();
        final Class<?> define = loader.define(jiteClass.getClassName().replace("/", "."), bytes);


        try {
            final Method execute;
            final Method[] declaredMethods = define.getDeclaredMethods();
            for (Method declaredMethod : declaredMethods) {
                System.out.println(declaredMethod);
            }
            execute = define.getDeclaredMethod("execute");
            final Object invoke = execute.invoke(null);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        return null;
    }

    private void emitInstruction(JiteClass jiteClass, HashMap<Label, LabelNode> jumpMap, CodeBlock block, Instruction instruction) {
        switch (instruction.getOperation()) {
            case DEFINE_FUNCTION:
                emitFunction(jiteClass, jumpMap, block, (DefineFunction) instruction);
                break;
            case ADD:
                emitAdd(block, (Add) instruction);
                break;
            case SUB:
                emitSub(block, (Sub) instruction);
                break;
            case COPY:
                emitCopy(block, (Copy) instruction);
                break;
            case BEQ:
                emitBEQ(block, (BEQ) instruction, jumpMap);
                break;
            case LT:
                emitLT(block, (LT) instruction);
                break;
            case JUMP:
                emitJump(block, (Jump) instruction, jumpMap);
                break;
            case RETURN:
                emitReturn(block, (Return) instruction, jumpMap);
                break;
            case RECEIVE_FUNCTION_PARAM:
                emitReceiveFunctionParameter(block, (ReceiveFunctionParameter) instruction, jumpMap);
                break;
            case CALL:
                emitCall(jiteClass, block, (Call) instruction, jumpMap);
                break;
        }
    }

    private void emitCall(JiteClass jiteClass, CodeBlock block, Call instruction, HashMap<Label, LabelNode> jumpMap) {
        emitOperand(block, instruction.getIdentifier());
        block.checkcast(p(Operand.class));
        block.aload(1);
        block.aconst_null();
        block.invokevirtual(p(Operand.class), "retrieve", sig(Object.class, ExecutionContext.class, Object[].class));
        block.aload(1);
        for (Operand operand : instruction.getArgs()) {
            emitOperand(block, operand);
        }
        block.invokestatic(jiteClass.getClassName(), scope.getSyntheticMethodName(), scope.getSyntheticSignature());
//        block.invokeinterface(p(JSCallable.class), "call", sig(Object.class, ExecutionContext.class));
    }

    private void emitSub(CodeBlock block, Sub instruction) {
        emitOperand(block, instruction.getLHS());
        emitOperand(block, instruction.getRHS());
        block.invokestatic(p(IRByteCodeCompiler.class), "sub", sig(Object.class, Object.class, Object.class));
        storeResult(block, instruction.getResult());
    }

    private void emitReceiveFunctionParameter(CodeBlock block, ReceiveFunctionParameter instruction, HashMap<Label, LabelNode> jumpMap) {
        block
                .aload(1)
                .pushInt(instruction.getIndex())
                .invokevirtual(p(ExecutionContext.class), "getFunctionParameter", sig(Object.class, int.class));

    }

    private void emitReturn(CodeBlock block, Return instruction, HashMap<Label, LabelNode> jumpMap) {
        emitOperand(block, instruction.getValue());
        block.areturn();
    }

    private void emitFunction(JiteClass jiteClass, HashMap<Label, LabelNode> jumpMap, CodeBlock block, DefineFunction instruction) {
        final FunctionScope functionScope = instruction.getScope();
        final String[] parameterNames = functionScope.getParameterNames();
        final CodeBlock fnBlock = new CodeBlock();
        final List<BasicBlock> blocks = functionScope.prepareForCompilation();
        for (BasicBlock bb : blocks) {
            for (Instruction fnInstr : bb.getInstructions()) {
                emitInstruction(jiteClass, jumpMap, fnBlock, fnInstr);
            }
        }
        if (!fnBlock.returns()) {
            fnBlock.aconst_null().areturn();
        }
        final String methodName = nextSyntheticMethodName(functionScope);
        final String syntheticSignature = sig(Object.class, params(Object.class, ExecutionContext.class, Object.class, parameterNames.length));
        jiteClass.defineMethod(methodName, Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, syntheticSignature, fnBlock);
        functionScope.setSyntheticMethodName(methodName);
        functionScope.setSyntheticSignature(syntheticSignature);
    }

    private void emitJump(CodeBlock block, Jump instruction, HashMap<Label, LabelNode> jumpMap) {
        block.go_to(jumpMap.get(instruction.getTarget()));

    }

    private void emitLT(CodeBlock block, LT instruction) {
        emitOperand(block, instruction.getArg1());
        emitOperand(block, instruction.getArg2());
        block.invokestatic(p(IRByteCodeCompiler.class), "lt", sig(Boolean.class, Object.class, Object.class));
        storeResult(block, instruction.getResult());
    }

    private void emitBEQ(CodeBlock block, BEQ instruction, Map<Label, LabelNode> jumpMap) {
        emitOperand(block, instruction.getArg1());
        block.invokevirtual(p(Boolean.class), "booleanValue", sig(boolean.class));
        emitOperand(block, instruction.getArg2());
        final Label label = instruction.getTarget();
        System.out.println("looking for label: " + label);
        final LabelNode node = jumpMap.get(label);
        block.if_icmpeq(node);
    }

    private void emitOperand(CodeBlock block, Operand operand) {
        switch (operand.getType()) {
            case DYNAMIC_VAR:
                emitDynamicVar(block, (DynamicVariable) operand);
                break;
            case INTEGER:
                emitInteger(block, (IntegerNumber) operand);
                break;
            case TEMP_VAR:
                emitTempVar(block, (TemporaryVariable) operand);
                break;
            case LOCAL_VAR:
                emitLocalVar(block, (LocalVariable) operand);
                break;
            case BOOLEAN:
                emitBoolean(block, (BooleanLiteral) operand);
                break;
            case UNDEFINED:
                emitUndefined(block, (Undefined) operand);
                break;
        }
    }

    private void emitUndefined(CodeBlock block, Undefined operand) {
        block
                .getstatic(p(Types.class), "UNDEFINED", ci(Types.Undefined.class));
    }

    public CodeBlock jsGetValue() {
        CodeBlock codeBlock = new CodeBlock()
                // IN: reference
                .aload(1)
                        // reference context
                .swap()
                        // context reference
                .invokestatic(p(Types.class), "getValue", sig(Object.class, ExecutionContext.class, Object.class));
        return codeBlock;
    }

    private void emitDynamicVar(CodeBlock block, DynamicVariable operand) {
        block
                .aload(1)
                        // context
                .ldc(operand.getName())
                        // context name
                .invokevirtual(p(ExecutionContext.class), "resolve", sig(Object.class, String.class))
                        // reference
                .aload(1)
                        // reference context
                .swap()
                        // context reference
                .invokestatic(p(Types.class), "getValue", sig(Object.class, ExecutionContext.class, Object.class));
    }

    private void emitBoolean(CodeBlock block, BooleanLiteral operand) {
        block.pushBoolean((Boolean) operand.retrieve(null, null));
    }

    private void emitLocalVar(CodeBlock block, LocalVariable operand) {
        block
                .aload(1)
                .invokevirtual(p(ExecutionContext.class), "getVars", sig(VariableValues.class))
                .pushInt(operand.getOffset())
                .pushInt(operand.getDepth())
                .invokevirtual(p(VariableValues.class), "getVar", sig(Object.class, int.class, int.class));
    }

    private void emitTempVar(CodeBlock block, TemporaryVariable operand) {
        block.aload(getTempVarOffset() + operand.getOffset());
    }

    private int getTempVarOffset() {
        return getLocalVarOffset() + scope.getLocalVariableSize();
    }

    private int getLocalVarOffset() {
        return 1;
    }

    private void emitInteger(CodeBlock block, IntegerNumber operand) {
        block.pushInt((int) operand.getValue());
        block.invokestatic(p(Integer.class), "valueOf", sig(Integer.class, int.class));
    }

    private void emitAdd(CodeBlock block, Add instruction) {
        emitOperand(block, instruction.getLHS());
        emitOperand(block, instruction.getRHS());
        block.invokestatic(p(IRByteCodeCompiler.class), "add", sig(Object.class, Object.class, Object.class));
        storeResult(block, instruction.getResult());
    }

    private void emitCopy(CodeBlock block, Copy instruction) {
        emitOperand(block, instruction.getValue());
        final Variable result = instruction.getResult();
        storeResult(block, result);
    }

    private void storeResult(CodeBlock block, Variable result) {
        int offset = 0;
        switch (result.getType()) {
            case TEMP_VAR:
                offset = getTempVarOffset() + ((TemporaryVariable) result).getOffset();
                block.astore(offset);
                break;
            case LOCAL_VAR:
                offset = ((LocalVariable) result).getOffset();
                int depth = ((LocalVariable) result).getDepth();
                block
                        .aload(1)
                                // ? EC
                        .swap()
                                // EC ?
                        .invokevirtual(p(ExecutionContext.class), "getVars", sig(VariableValues.class))
                                // EC ? VV
                        .swap()
                                // EC VV ?
                        .pushInt(offset)
                                // EC VV ? offset
                        .swap()
                                // EC VV offset ?
                        .pushInt(depth)
                                // EC VV offset ? depth
                        .swap()
                                // EC VV offset depth ?
                        .invokevirtual(p(VariableValues.class), "setVar", sig(Object.class, int.class, int.class, Object.class));
                break;
        }
    }


    private String nextSyntheticMethodName(FunctionScope scope) {
        final String[] parameterNames = scope.getParameterNames();
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parameterNames.length; i++) {
            builder.append(parameterNames[i]);
            builder.append("_");
        }
        return "m_" + builder.toString() + "_" + methodCounter.getAndIncrement();
    }

    private String nextCompiledFunctionName() {
        return "Function" + "_" + compiledFunctionCounter.getAndIncrement();
    }

    public static Boolean lt(Object a, Object b) {
        return ((Integer) a).compareTo((Integer) b) == -1;
    }

    public static Object add(Object a, Object b) {
        return ((Integer) a) + ((Integer) b);
    }

    public static Object sub(Object a, Object b) {
        return ((Integer) a) - ((Integer) b);
    }

    public JSFunction compileFunction(ExecutionContext context) {
        final String methodName = nextSyntheticMethodName(scope);
        final String syntheticSignature = sig(Object.class, params(Object.class, ExecutionContext.class, Object.class, scope.getParameterNames().length));
        scope.setSyntheticMethodName(methodName);
        scope.setSyntheticSignature(syntheticSignature);
        System.out.println("kicking compilation");
        final JiteClass jiteClass = new JiteClass("org/dynjs/gen/" + nextCompiledFunctionName(), p(AbstractFunction.class), new String[]{p(JSCallable.class)});
        jiteClass.defineMethod("<init>", Opcodes.ACC_PUBLIC, sig(void.class, GlobalObject.class, LexicalEnvironment.class, boolean.class, String[].class),
                new CodeBlock()
                        .aload(CodeGeneratingVisitor.Arities.THIS)
                                // this
                        .aload(1)
                                // this globalobject
                        .aload(2)
                                // this globalobject lexicalenvironment
                        .iload(3)
                                // this globalobject lexicalenvironment strict
                        .aload(4)
                                // this globalobject lexicalenvironment strict formalparamenters[]
                        .invokespecial(p(AbstractFunction.class), "<init>", sig(void.class, GlobalObject.class, LexicalEnvironment.class, boolean.class, String[].class))
                        .voidreturn()
        );

        final int varOffset = 2;
        final List<BasicBlock> blockList = this.blockList;
        Object[] temps = new Object[scope.getTemporaryVariableSize()];
        Object[] vars = new Object[scope.getLocalVariableSize()];
        final HashMap<Label, LabelNode> jumpMap = new HashMap<>();

        CodeBlock block = new CodeBlock();
        // first pass for gathering labels
        for (BasicBlock bb : blockList) {
            final Label label = bb.getLabel();
            System.out.println("label: " + label);
            final LabelNode labelNode = new LabelNode();
            jumpMap.put(label, labelNode);
        }

        // second pass for emitting
        for (BasicBlock bb : blockList) {
            block.label(jumpMap.get(bb.getLabel()));

            for (Instruction instruction : bb.getInstructions()) {
                emitInstruction(jiteClass, jumpMap, block, instruction);
            }
        }

        if (!block.returns()) {
            block.aconst_null().areturn();
        }



        jiteClass.defineMethod(methodName, Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, syntheticSignature, block);

        jiteClass.defineMethod("call", Opcodes.ACC_PUBLIC, sig(Object.class, ExecutionContext.class), new CodeBlock().aconst_null().areturn());
        final byte[] bytes = jiteClass.toBytes();
        System.out.println("compiled" + bytes);
        ClassReader reader = new ClassReader(bytes);
        CheckClassAdapter.verify(reader, context.getClassLoader(), true, new PrintWriter(System.out));

        final DynamicClassLoader loader = new DynamicClassLoader();
        final Class<?> define = loader.define(jiteClass.getClassName().replace("/", "."), bytes);
        try {
            final Constructor<?> constructor = define.getDeclaredConstructor(GlobalObject.class, LexicalEnvironment.class, boolean.class, String[].class);
            final JSFunction function = (JSFunction) constructor.newInstance(context.getGlobalObject(), context.getLexicalEnvironment(), strict, scope.getParameterNames());
            return function;
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }
}
