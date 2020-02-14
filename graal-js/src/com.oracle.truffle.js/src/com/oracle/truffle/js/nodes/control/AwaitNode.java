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
package com.oracle.truffle.js.nodes.control;

import java.util.Set;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AnalysisTags;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.access.JSTargetableNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.instrumentation.JSMaterializedInvokeTargetableNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.promise.NewPromiseCapabilityNode;
import com.oracle.truffle.js.nodes.promise.PerformPromiseThenNode;
import com.oracle.truffle.js.nodes.promise.PromiseResolveNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.PromiseCapabilityRecord;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class AwaitNode extends JavaScriptNode implements ResumableNode, SuspendNode {

    @Child protected JavaScriptNode expression;
    @Child protected JSReadFrameSlotNode readAsyncResultNode;
    @Child protected JSReadFrameSlotNode readAsyncContextNode;
    @Child private NewPromiseCapabilityNode newPromiseCapabilityNode;
    @Child private PerformPromiseThenNode performPromiseThenNode;
    @Child private PromiseResolveNode promiseResolveNode;
    @Child private JSFunctionCallNode callPromiseResolveNode;
    @Child private PropertySetNode setPromiseIsHandledNode;
    @Child private PropertySetNode setAsyncContextNode;
    @Child private PropertySetNode setAsyncTargetNode;
    @Child private PropertySetNode setAsyncGeneratorNode;
    @Child private JSTargetableNode materializedInputNode;
    protected final JSContext context;
    private final ConditionProfile asyncTypeProf = ConditionProfile.createBinaryProfile();
    private final ConditionProfile resumptionTypeProf = ConditionProfile.createBinaryProfile();

    static final HiddenKey ASYNC_CONTEXT = new HiddenKey("AsyncContext");
    static final HiddenKey ASYNC_TARGET = new HiddenKey("AsyncTarget");
    static final HiddenKey ASYNC_GENERATOR = new HiddenKey("AsyncGenerator");

    protected AwaitNode(JSContext context, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode) {
        this(context, expression, readAsyncContextNode, readAsyncResultNode, null);
    }

    private AwaitNode(JSContext context, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode, JSTargetableNode materializedInputNode) {
        this.context = context;
        this.expression = expression;
        this.readAsyncResultNode = readAsyncResultNode;
        this.readAsyncContextNode = readAsyncContextNode;

        this.setAsyncContextNode = PropertySetNode.createSetHidden(ASYNC_CONTEXT, context);
        this.setAsyncTargetNode = PropertySetNode.createSetHidden(ASYNC_TARGET, context);
        this.setAsyncGeneratorNode = PropertySetNode.createSetHidden(ASYNC_GENERATOR, context);

        this.performPromiseThenNode = PerformPromiseThenNode.create(context);
        if (context.usePromiseResolve()) {
            this.promiseResolveNode = PromiseResolveNode.create(context);
        } else {
            this.callPromiseResolveNode = JSFunctionCallNode.createCall();
        }
        this.materializedInputNode = materializedInputNode;
    }

    public static AwaitNode create(JSContext context, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode) {
        return new AwaitNode(context, expression, readAsyncContextNode, readAsyncResultNode, null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = expression.execute(frame);
        return suspendAwait(frame, value);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == JSTags.ControlFlowBranchTag.class || tag == JSTags.InputNodeTag.class) {
            return true;
        }
        return super.hasTag(tag);
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializationNeeded() && (materializedTags.contains(JSTags.ControlFlowBranchTag.class) || materializedTags.contains(AnalysisTags.ControlFlowBranchTag.class))) {
            JSTargetableNode materializedInput = JSMaterializedInvokeTargetableNode.EchoTargetValueNode.create();
            AwaitNode materialized = new AwaitNode(context, expression, readAsyncContextNode, readAsyncResultNode, materializedInput);
            transferSourceSection(this, materialized);
            return materialized;
        }
        return this;
    }

    private boolean materializationNeeded() {
        return this.materializedInputNode == null && getClass() == AwaitNode.class;
    }

    protected final Object suspendAwait(VirtualFrame frame, Object value) {
        Object[] initialState = (Object[]) readAsyncContextNode.execute(frame);
        CallTarget resumeTarget = (CallTarget) initialState[0];
        Object generatorOrCapability = initialState[1];
        MaterializedFrame asyncContext = (MaterializedFrame) initialState[2];

        if (asyncTypeProf.profile(generatorOrCapability instanceof PromiseCapabilityRecord)) {
            Object parentPromise = ((PromiseCapabilityRecord) generatorOrCapability).getPromise();
            context.notifyPromiseHook(-1 /* parent info */, (DynamicObject) parentPromise);
        }

        DynamicObject promise = promiseResolve(value);
        DynamicObject onFulfilled = createAwaitFulfilledFunction(resumeTarget, asyncContext, generatorOrCapability);
        DynamicObject onRejected = createAwaitRejectedFunction(resumeTarget, asyncContext, generatorOrCapability);
        PromiseCapabilityRecord throwawayCapability = newThrowawayCapability();

        context.notifyPromiseHook(-1 /* parent info */, promise);
        if (materializedInputNode != null) {
            materializedInputNode.executeWithTarget(frame, promise);
        }
        performPromiseThenNode.execute(promise, onFulfilled, onRejected, throwawayCapability);
        throw YieldException.AWAIT_NULL; // value is ignored
    }

    private DynamicObject promiseResolve(Object value) {
        if (context.usePromiseResolve()) {
            return promiseResolveNode.execute(context.getRealm().getPromiseConstructor(), value);
        } else {
            PromiseCapabilityRecord promiseCapability = newPromiseCapability();
            Object resolve = promiseCapability.getResolve();
            callPromiseResolveNode.executeCall(JSArguments.createOneArg(Undefined.instance, resolve, value));
            return promiseCapability.getPromise();
        }
    }

    private PromiseCapabilityRecord newThrowawayCapability() {
        if (context.getEcmaScriptVersion() >= JSConfig.ECMAScript2019) {
            return null;
        }
        if (setPromiseIsHandledNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            setPromiseIsHandledNode = insert(PropertySetNode.createSetHidden(JSPromise.PROMISE_IS_HANDLED, context));
        }
        PromiseCapabilityRecord throwawayCapability = newPromiseCapability();
        setPromiseIsHandledNode.setValueBoolean(throwawayCapability.getPromise(), true);
        return throwawayCapability;
    }

    @Override
    public Object getNodeObject() {
        return JSTags.createNodeObjectDescriptor("type", JSTags.ControlFlowBranchTag.Type.Await.name());
    }

    @Override
    public Object resume(VirtualFrame frame) {
        int index = getStateAsInt(frame);
        if (index == 0) {
            Object value = expression.execute(frame);
            setState(frame, 1);
            return suspendAwait(frame, value);
        } else {
            setState(frame, 0);
            return resumeAwait(frame);
        }
    }

    protected Object resumeAwait(VirtualFrame frame) {
        // We have been restored at this point. The frame contains the resumption state.
        Completion result = (Completion) readAsyncResultNode.execute(frame);
        if (materializedInputNode != null) {
            materializedInputNode.executeWithTarget(frame, result.getValue());
        }
        if (resumptionTypeProf.profile(result.isNormal())) {
            return result.getValue();
        } else {
            assert result.isThrow();
            Object reason = result.getValue();
            throw UserScriptException.create(reason, this);
        }
    }

    private PromiseCapabilityRecord newPromiseCapability() {
        if (newPromiseCapabilityNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            newPromiseCapabilityNode = insert(NewPromiseCapabilityNode.create(context));
        }
        return newPromiseCapabilityNode.executeDefault();
    }

    private DynamicObject createAwaitFulfilledFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitFulfilled, (c) -> createAwaitFulfilledImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setAsyncTargetNode.setValue(function, resumeTarget);
        setAsyncContextNode.setValue(function, asyncContext);
        setAsyncGeneratorNode.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAwaitFulfilledImpl(JSContext context) {
        class AwaitFulfilledRootNode extends JavaScriptRootNode {
            @Child private JavaScriptNode valueNode = AccessIndexedArgumentNode.create(0);
            @Child private PropertyGetNode getAsyncTarget = PropertyGetNode.createGetHidden(ASYNC_TARGET, context);
            @Child private PropertyGetNode getAsyncContext = PropertyGetNode.createGetHidden(ASYNC_CONTEXT, context);
            @Child private PropertyGetNode getAsyncGenerator = PropertyGetNode.createGetHidden(ASYNC_GENERATOR, context);
            @Child private AwaitResumeNode awaitResumeNode = AwaitResumeNode.create(false);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                CallTarget asyncTarget = (CallTarget) getAsyncTarget.getValue(functionObject);
                Object asyncContext = getAsyncContext.getValue(functionObject);
                Object generator = getAsyncGenerator.getValue(functionObject);
                Object value = valueNode.execute(frame);
                return awaitResumeNode.execute(asyncTarget, asyncContext, generator, value);
            }
        }
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new AwaitFulfilledRootNode());
        return JSFunctionData.createCallOnly(context, callTarget, 1, "");
    }

    private DynamicObject createAwaitRejectedFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitRejected, (c) -> createAwaitRejectedImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setAsyncTargetNode.setValue(function, resumeTarget);
        setAsyncContextNode.setValue(function, asyncContext);
        setAsyncGeneratorNode.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAwaitRejectedImpl(JSContext context) {
        class AwaitRejectedRootNode extends JavaScriptRootNode {
            @Child private JavaScriptNode reasonNode = AccessIndexedArgumentNode.create(0);
            @Child private PropertyGetNode getAsyncTargetNode = PropertyGetNode.createGetHidden(ASYNC_TARGET, context);
            @Child private PropertyGetNode getAsyncContextNode = PropertyGetNode.createGetHidden(ASYNC_CONTEXT, context);
            @Child private PropertyGetNode getAsyncGeneratorNode = PropertyGetNode.createGetHidden(ASYNC_GENERATOR, context);
            @Child private AwaitResumeNode awaitResumeNode = AwaitResumeNode.create(true);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                CallTarget asyncTarget = (CallTarget) getAsyncTargetNode.getValue(functionObject);
                Object asyncContext = getAsyncContextNode.getValue(functionObject);
                Object generator = getAsyncGeneratorNode.getValue(functionObject);
                Object reason = reasonNode.execute(frame);
                return awaitResumeNode.execute(asyncTarget, asyncContext, generator, reason);
            }
        }
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new AwaitRejectedRootNode());
        return JSFunctionData.createCallOnly(context, callTarget, 1, "");
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        JavaScriptNode expressionCopy = cloneUninitialized(expression);
        JSReadFrameSlotNode asyncResultCopy = cloneUninitialized(readAsyncResultNode);
        JSReadFrameSlotNode asyncContextCopy = cloneUninitialized(readAsyncContextNode);
        return create(context, expressionCopy, asyncContextCopy, asyncResultCopy);
    }
}
