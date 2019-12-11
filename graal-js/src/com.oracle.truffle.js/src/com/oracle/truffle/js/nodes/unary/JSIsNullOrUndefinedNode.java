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
package com.oracle.truffle.js.nodes.unary;

import java.util.Set;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.instrumentation.AnalysisTags;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.ObjectType;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantNullNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantUndefinedNode;
import com.oracle.truffle.js.nodes.binary.JSEqualNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BinaryOperationTag;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.LargeInteger;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.objects.JSLazyString;

/**
 * This node optimizes the check whether the argument is null or undefined. Used from the
 * {@link JSEqualNode} for optimizing {@code a == undefined;} and {@code a == null;}
 *
 */
public abstract class JSIsNullOrUndefinedNode extends JSUnaryNode {
    protected static final int MAX_SHAPE_COUNT = 1;
    protected static final int MAX_TYPE_COUNT = 1;
    protected static final int MAX_CLASSES = 3;

    private final boolean isLeft;
    private final boolean isUndefined;

    protected JSIsNullOrUndefinedNode(JavaScriptNode operand, boolean isUndefined, boolean isLeft) {
        super(operand);
        this.isUndefined = isUndefined;
        this.isLeft = isLeft;
    }

    public abstract boolean executeBoolean(Object input);

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == BinaryOperationTag.class) {
            return true;
        } else {
            return super.hasTag(tag);
        }
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializedTags.contains(BinaryOperationTag.class) || materializedTags.contains(AnalysisTags.BinaryOperationTag.class)) {
            JSConstantNode constantNode = isUndefined ? JSConstantNode.createUndefined() : JSConstantNode.createNull();
            JavaScriptNode left = isLeft ? constantNode : getOperand();
            JavaScriptNode right = isLeft ? getOperand() : constantNode;
            JavaScriptNode materialized = JSEqualNode.createUnoptimized(left, right);
            transferSourceSectionAddExpressionTag(this, constantNode);
            transferSourceSectionAndTags(this, materialized);
            return materialized;
        } else {
            return this;
        }
    }

    @Specialization(guards = "isJSNull(operand)")
    protected static boolean doNull(@SuppressWarnings("unused") Object operand) {
        return true;
    }

    @Specialization(guards = "isUndefined(operand)")
    protected static boolean doUndefined(@SuppressWarnings("unused") Object operand) {
        return true;
    }

    @Specialization
    protected static boolean doSymbol(@SuppressWarnings("unused") Symbol operand) {
        return false;
    }

    @Specialization
    protected static boolean doLazyString(@SuppressWarnings("unused") JSLazyString operand) {
        return false;
    }

    @Specialization
    protected static boolean doLargeInteger(@SuppressWarnings("unused") LargeInteger operand) {
        return false;
    }

    @Specialization
    protected static boolean doBigInt(@SuppressWarnings("unused") BigInt operand) {
        return false;
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"isJSObject", "object.getShape() == cachedShape"}, limit = "MAX_SHAPE_COUNT")
    protected static boolean doJSObjectCachedShape(DynamicObject object,
                    @Cached("isJSType(object)") boolean isJSObject,
                    @Cached("object.getShape()") Shape cachedShape) {
        assert !JSGuards.isNullOrUndefined(object);
        return false;
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"isJSObject", "object.getShape().getObjectType() == cachedType"}, replaces = {"doJSObjectCachedShape"}, limit = "MAX_TYPE_COUNT")
    protected static boolean doJSObjectCachedType(DynamicObject object,
                    @Cached("isJSType(object)") boolean isJSObject,
                    @Cached("object.getShape().getObjectType()") ObjectType cachedType) {
        assert !JSGuards.isNullOrUndefined(object);
        return false;
    }

    @Specialization(guards = {"isJSType(object)"}, replaces = {"doJSObjectCachedType"})
    protected static boolean doJSObject(DynamicObject object,
                    @Cached("createBinaryProfile()") ConditionProfile resultProfile) {
        return resultProfile.profile(!JSRuntime.isObject(object));
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"operand != null", "cachedClass != null", "cachedClass == operand.getClass()"}, limit = "MAX_CLASSES")
    protected static boolean doJSValueCached(Object operand,
                    @Cached("getNonTruffleObjectClass(operand)") Class<?> cachedClass) {
        return false;
    }

    @Specialization(guards = {"isJSType(operand)"}, replaces = {"doJSValueCached"})
    protected static boolean doJSValueJSObject(DynamicObject operand) {
        return JSGuards.isNullOrUndefined(operand);
    }

    @Specialization(guards = {"!isTruffleObject(operand)"}, replaces = {"doJSValueCached"})
    protected static boolean doJSValue(@SuppressWarnings("unused") Object operand) {
        return false;
    }

    @Specialization(guards = "isForeignObject(operand)", limit = "1")
    protected boolean doForeign(TruffleObject operand,
                    @CachedLibrary("operand") InteropLibrary interop) {
        return interop.isNull(operand);
    }

    public static JSIsNullOrUndefinedNode createFromEquals(JavaScriptNode left, JavaScriptNode right) {
        assert isNullOrUndefined(left) || isNullOrUndefined(right);
        boolean isLeft = isNullOrUndefined(left);
        JavaScriptNode operand = isLeft ? right : left;
        JavaScriptNode constant = isLeft ? left : right;
        boolean isUndefined = constant instanceof JSConstantUndefinedNode;
        return JSIsNullOrUndefinedNodeGen.create(operand, isUndefined, isLeft);
    }

    public static JSIsNullOrUndefinedNode create() {
        return JSIsNullOrUndefinedNodeGen.create(null, true, true);
    }

    private static boolean isNullOrUndefined(JavaScriptNode node) {
        return node instanceof JSConstantUndefinedNode || node instanceof JSConstantNullNode;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return JSIsNullOrUndefinedNodeGen.create(cloneUninitialized(getOperand()), isUndefined, isLeft);
    }
}
