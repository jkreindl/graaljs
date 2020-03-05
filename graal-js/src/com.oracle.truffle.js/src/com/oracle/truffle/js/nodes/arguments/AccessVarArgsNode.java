/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.nodes.arguments;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AnalysisTags;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.runtime.JSArguments;

import java.util.Arrays;
import java.util.Set;

public class AccessVarArgsNode extends AccessIndexedArgumentNode {
    private static final int MAX_UNROLL = 250;
    private static final int UNINITIALIZED = -2;
    /** Unstable or too large argument count. */
    private static final int UNSTABLE = -1;

    /** Expected/profiled stable user argument count or {@link #UNSTABLE}. */
    @CompilationFinal private int userArgumentCount;

    AccessVarArgsNode(int paramIndex) {
        super(paramIndex);
        this.userArgumentCount = UNINITIALIZED;
    }

    public static AccessVarArgsNode create(int paramIndex) {
        return new AccessVarArgsNode(paramIndex);
    }

    @Override
    public final Object[] execute(VirtualFrame frame) {
        return executeObjectArray(frame);
    }

    @Override
    public final Object[] executeObjectArray(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        int currentUserArgumentCount = JSArguments.getUserArgumentCount(arguments);

        if (profile(index >= currentUserArgumentCount)) {
            return JSArguments.EMPTY_ARGUMENTS_ARRAY;
        } else {
            int constantUserArgumentCount = userArgumentCount;

            if (constantUserArgumentCount == UNINITIALIZED) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (currentUserArgumentCount <= MAX_UNROLL) {
                    constantUserArgumentCount = currentUserArgumentCount;
                } else {
                    constantUserArgumentCount = UNSTABLE;
                }
                userArgumentCount = constantUserArgumentCount;
            }

            if (constantUserArgumentCount == UNSTABLE) {
                return getArgumentsArrayWithoutExplosion(frame, arguments, currentUserArgumentCount);
            }

            if (constantUserArgumentCount != currentUserArgumentCount) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                constantUserArgumentCount = currentUserArgumentCount;
                userArgumentCount = UNSTABLE;
            }

            return getArgumentsArray(frame, arguments, constantUserArgumentCount);
        }
    }

    @ExplodeLoop
    Object[] getArgumentsArray(@SuppressWarnings("unused") VirtualFrame frame, Object[] arguments, int constantUserArgumentCount) {
        int length = constantUserArgumentCount - index;
        Object[] varArgs = new Object[length];
        for (int i = 0; i < length; i++) {
            varArgs[i] = JSArguments.getUserArgument(arguments, i + index);
        }
        return varArgs;
    }

    Object[] getArgumentsArrayWithoutExplosion(@SuppressWarnings("unused") VirtualFrame frame, Object[] arguments, int currentUserArgumentCount) {
        int length = currentUserArgumentCount - index;
        Object[] varArgs = new Object[length];
        for (int i = 0; i < length; i++) {
            varArgs[i] = JSArguments.getUserArgument(arguments, i + index);
        }
        return varArgs;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return new AccessVarArgsNode(index);
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializedTags.contains(JSTags.ArgReadTag.class) || materializedTags.contains(AnalysisTags.ReadArgumentTag.class)) {
            final MaterializedAccessVarArgsNode materializedNode = new MaterializedAccessVarArgsNode(index);
            transferSourceSection(this, materializedNode);
            return materializedNode;
        } else {
            return this;
        }
    }

    private static final class MaterializedAccessVarArgsNode extends AccessVarArgsNode {

        private static final JavaScriptNode[] NO_NODES = new JavaScriptNode[0];

        @Children private JavaScriptNode[] readNodes;

        MaterializedAccessVarArgsNode(int paramIndex) {
            super(paramIndex);
            this.readNodes = NO_NODES;
        }

        @Override
        @ExplodeLoop
        Object[] getArgumentsArray(VirtualFrame frame, Object[] arguments, int constantUserArgumentCount) {
            int length = constantUserArgumentCount - index;

            CompilerAsserts.compilationConstant(length);
            if (readNodes.length < length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                extendReadNodes(length);
            } else {
                CompilerAsserts.compilationConstant(readNodes.length);
            }

            Object[] varArgs = new Object[length];
            for (int i = 0; i < length; i++) {
                varArgs[i] = readNodes[i].execute(frame);
            }
            return varArgs;
        }

        @Override
        Object[] getArgumentsArrayWithoutExplosion(VirtualFrame frame, Object[] arguments, int currentUserArgumentCount) {
            int length = currentUserArgumentCount - index;

            if (readNodes.length < length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                extendReadNodes(length);
            } else {
                CompilerAsserts.compilationConstant(readNodes.length);
            }

            Object[] varArgs = new Object[length];
            for (int i = 0; i < length; i++) {
                varArgs[i] = readNodes[i].execute(frame);
            }
            return varArgs;
        }

        private void extendReadNodes(int newLength) {
            CompilerAsserts.neverPartOfCompilation();
            final int oldLength = readNodes.length;
            readNodes = Arrays.copyOf(readNodes, newLength);
            for (int i = oldLength; i < readNodes.length; i++) {
                final ReadVarArgNode readVarArgNode = new ReadVarArgNode(i + index);
                AccessVarArgsNode.transferSourceSectionAddExpressionTag(this, readVarArgNode);
                readNodes[i] = insert(readVarArgNode);
                notifyInserted(readVarArgNode);
            }
        }

        @Override
        public boolean hasTag(Class<? extends Tag> tag) {
            // the nodes actually reading the arguments should be instrumented
            if (tag == JSTags.ArgReadTag.class) {
                return false;
            } else {
                return super.hasTag(tag);
            }
        }

        @Override
        public boolean isInstrumentable() {
            return false;
        }

        @Override
        protected JavaScriptNode copyUninitialized() {
            return new MaterializedAccessVarArgsNode(index);
        }
    }

    private static final class ReadVarArgNode extends JavaScriptNode {

        private final int index;

        ReadVarArgNode(int index) {
            this.index = index;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] jsArguments = frame.getArguments();
            return JSArguments.getUserArgument(jsArguments, index);
        }

        @Override
        public boolean hasTag(Class<? extends Tag> tag) {
            return tag == JSTags.ArgReadTag.class || super.hasTag(tag);
        }

        @Override
        public Object getNodeObject() {
            return JSTags.createNodeObjectDescriptor("index", index);
        }
    }
}
