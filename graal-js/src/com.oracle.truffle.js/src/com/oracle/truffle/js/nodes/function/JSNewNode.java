/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.function;

import java.lang.reflect.Modifier;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Executed;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AnalysisTags;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.instrumentation.JSInputGeneratingNodeWrapper;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ObjectAllocationTag;
import com.oracle.truffle.js.nodes.instrumentation.NodeObjectDescriptor;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.nodes.interop.JSForeignToJSTypeNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.builtins.JSAdapter;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.java.JavaAccess;
import com.oracle.truffle.js.runtime.java.JavaPackage;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;

/**
 * 11.2.2 The new Operator.
 */
@ImportStatic(value = {JSProxy.class})
@ReportPolymorphism
public abstract class JSNewNode extends JavaScriptNode {

    @Child @Executed protected JavaScriptNode targetNode;

    @Child private JSFunctionCallNode callNew;
    @Child private JSFunctionCallNode callNewTarget;
    @Child private AbstractFunctionArgumentsNode arguments;

    protected final JSContext context;

    protected JSNewNode(JSContext context, JavaScriptNode targetNode, AbstractFunctionArgumentsNode arguments) {
        this.context = context;
        this.targetNode = targetNode;
        this.arguments = arguments;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == ObjectAllocationTag.class) {
            return true;
        }
        return super.hasTag(tag);
    }

    @Override
    public Object getNodeObject() {
        NodeObjectDescriptor descriptor = JSTags.createNodeObjectDescriptor();
        descriptor.addProperty("isNew", true);
        descriptor.addProperty("isInvoke", false);
        return descriptor;
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializationNeeded(materializedTags)) {
            JavaScriptNode newTarget = JSInputGeneratingNodeWrapper.create(getTarget());
            JSNewNode materialized = JSNewNodeGen.create(context, newTarget, arguments);
            arguments.materializeInstrumentableArguments();
            transferSourceSectionAndTags(this, materialized);
            return materialized;
        }
        return this;
    }

    private boolean materializationNeeded(Set<Class<? extends Tag>> materializedTags) {
        if (materializedTags.contains(ObjectAllocationTag.class) || materializedTags.contains(AnalysisTags.ObjectAllocationTag.class)) {
            return (!getTarget().hasSourceSection() && !(getTarget() instanceof JSInputGeneratingNodeWrapper));
        }
        return false;
    }

    public static JSNewNode create(JSContext context, JavaScriptNode function, JavaScriptNode[] arguments) {
        return JSNewNodeGen.create(context, function, JSFunctionArgumentsNode.create(context, arguments));
    }

    public JavaScriptNode getTarget() {
        return targetNode;
    }

    @Specialization(guards = "isJSFunction(target)")
    public Object doNewReturnThis(VirtualFrame frame, DynamicObject target) {
        int userArgumentCount = arguments.getCount(frame);
        Object[] args = JSArguments.createInitial(JSFunction.CONSTRUCT, target, userArgumentCount);
        args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT);
        return getCallNew().executeCall(args);
    }

    @Specialization(guards = "isJSAdapter(target)")
    public Object doJSAdapter(VirtualFrame frame, DynamicObject target) {
        Object newFunction = JSObject.get(JSAdapter.getAdaptee(target), JSAdapter.NEW);
        if (JSFunction.isJSFunction(newFunction)) {
            Object[] args = getAbstractFunctionArguments(frame);
            return JSFunction.call((DynamicObject) newFunction, target, args);
        } else {
            return Undefined.instance;
        }
    }

    /**
     * Implements [[Construct]] for Proxy.
     */
    @Specialization(guards = "isProxy(proxy)")
    protected Object doNewJSProxy(VirtualFrame frame, DynamicObject proxy) {
        if (!JSRuntime.isConstructorProxy(proxy)) {
            throw Errors.createTypeErrorNotAFunction(proxy, this);
        }
        DynamicObject handler = JSProxy.getHandlerChecked(proxy);
        TruffleObject target = JSProxy.getTarget(proxy);
        TruffleObject trap = JSProxy.getTrapFromObject(handler, JSProxy.CONSTRUCT);
        if (trap == Undefined.instance) {
            if (JSObject.isJSObject(target)) {
                // Construct(F=target, argumentsList=frame, newTarget=proxy)
                int userArgumentCount = arguments.getCount(frame);
                Object[] args = JSArguments.createInitialWithNewTarget(JSFunction.CONSTRUCT, target, proxy, userArgumentCount);
                args = arguments.executeFillObjectArray(frame, args, JSArguments.RUNTIME_ARGUMENT_COUNT + 1);
                return getCallNewTarget().executeCall(args);
            } else {
                return JSInteropUtil.construct(target, getAbstractFunctionArguments(frame));
            }
        }
        Object[] args = getAbstractFunctionArguments(frame);
        Object[] trapArgs = new Object[]{target, JSArray.createConstantObjectArray(context, args), proxy};
        Object result = JSRuntime.call(trap, handler, trapArgs);
        if (!JSRuntime.isObject(result)) {
            throw Errors.createTypeErrorNotAnObject(result, this);
        }
        return result;
    }

    @TruffleBoundary
    @Specialization(guards = "isJavaPackage(target)")
    public Object createClassNotFoundError(DynamicObject target) {
        throw Errors.createTypeErrorClassNotFound(JavaPackage.getPackageName(target));
    }

    @TruffleBoundary
    private static void throwCannotExtendError(Class<?> target) {
        throw Errors.createTypeError("new cannot be used with non-public java type " + target.getTypeName() + ".");
    }

    @Specialization(guards = {"isForeignObject(target)"})
    public Object doNewForeignObject(VirtualFrame frame, TruffleObject target,
                    @CachedLibrary(limit = "5") InteropLibrary interop,
                    @Cached("create()") ExportValueNode convert,
                    @Cached("create()") JSForeignToJSTypeNode toJSType,
                    @Cached("createBinaryProfile()") ConditionProfile isHostClassProf,
                    @Cached("createBinaryProfile()") ConditionProfile isAbstractProf) {
        TruffleObject newTarget = target;
        int count = arguments.getCount(frame);
        Object[] args = new Object[count];
        args = arguments.executeFillObjectArray(frame, args, 0);
        // We need to convert (e.g., bind functions) before invoking the constructor
        for (int i = 0; i < args.length; i++) {
            args[i] = convert.execute(args[i]);
        }

        if (!JSTruffleOptions.SubstrateVM && context.isOptionNashornCompatibilityMode()) {
            TruffleLanguage.Env env = context.getRealm().getEnv();
            if (isHostClassProf.profile(count == 1 && env.isHostObject(target) && env.asHostObject(target) instanceof Class<?>)) {
                Class<?> javaType = (Class<?>) env.asHostObject(target);
                if (isAbstractProf.profile(Modifier.isAbstract(javaType.getModifiers()) && !javaType.isArray())) {
                    newTarget = extend(javaType, env);
                }
            }
        }

        try {
            return toJSType.executeWithTarget(interop.instantiate(newTarget, args));
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw Errors.createTypeErrorInteropException(target, e, "instantiate", this);
        }
    }

    @TruffleBoundary
    private TruffleObject extend(Class<?> type, TruffleLanguage.Env env) {
        assert !JSTruffleOptions.SubstrateVM;
        if (!Modifier.isPublic(type.getModifiers())) {
            throwCannotExtendError(type);
        }
        // Equivalent to Java.extend(type)
        JavaAccess.checkAccess(new Class<?>[]{type}, context);
        Class<?> adapterClass = context.getJavaAdapterClassFor(type);
        return (TruffleObject) env.asHostSymbol(adapterClass);
    }

    @TruffleBoundary
    @Specialization(guards = {"!isJSFunction(target)", "!isJSAdapter(target)", "!isProxy(target)", "!isJavaPackage(target)", "!isForeignObject(target)"})
    public Object createFunctionTypeError(Object target) {
        String targetStr = getTarget().expressionToString();
        Object targetForError = targetStr == null ? target : targetStr;
        if (context.isOptionNashornCompatibilityMode()) {
            throw Errors.createTypeErrorNotAFunction(targetForError, this);
        } else {
            throw Errors.createTypeErrorNotAConstructor(targetForError, this);
        }
    }

    private Object[] getAbstractFunctionArguments(VirtualFrame frame) {
        Object[] args = new Object[arguments.getCount(frame)];
        args = arguments.executeFillObjectArray(frame, args, 0);
        return args;
    }

    private JSFunctionCallNode getCallNewTarget() {
        if (callNewTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNewTarget = insert(JSFunctionCallNode.createNewTarget());
        }
        return callNewTarget;
    }

    private JSFunctionCallNode getCallNew() {
        if (callNew == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNew = insert(JSFunctionCallNode.createNew());
        }
        return callNew;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return JSNewNodeGen.create(context, cloneUninitialized(getTarget()), AbstractFunctionArgumentsNode.cloneUninitialized(arguments));
    }
}
