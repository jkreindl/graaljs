/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.test.polyglot;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.js.lang.JavaScriptLanguage;

public class InnerContextTest {
    @Test
    public void testInnerWithoutOuterJSContext() throws Exception {
        try (AutoCloseable languageScope = TestLanguage.withTestLanguage(new TestLanguage() {
            @Override
            protected CallTarget parse(ParsingRequest request) throws Exception {
                return Truffle.getRuntime().createCallTarget(new RootNode(languageInstance) {
                    @Override
                    public Object execute(VirtualFrame frame) {
                        return innerJS();
                    }

                    @TruffleBoundary
                    private Object innerJS() {
                        ContextReference<LanguageContext> contextReference = languageInstance.getContextReference();
                        TruffleLanguage.Env env = contextReference.get().getEnv();
                        TruffleContext innerContext = env.newContextBuilder().build();
                        Object prev = innerContext.enter();
                        try {
                            TruffleLanguage.Env innerEnv = contextReference.get().getEnv();
                            CallTarget answer = innerEnv.parse(com.oracle.truffle.api.source.Source.newBuilder(JavaScriptLanguage.ID, "42", "test.js").build());
                            return answer.call();
                        } finally {
                            innerContext.leave(prev);
                        }
                    }
                });
            }
        })) {
            try (Context context = Context.newBuilder(JavaScriptLanguage.ID, TestLanguage.ID).allowPolyglotAccess(PolyglotAccess.ALL).build()) {
                context.eval(Source.create(TestLanguage.ID, ""));
            }
            try (Context context = Context.newBuilder(JavaScriptLanguage.ID, TestLanguage.ID).allowPolyglotAccess(PolyglotAccess.ALL).build()) {
                context.initialize(JavaScriptLanguage.ID);
                context.eval(Source.create(TestLanguage.ID, ""));
            }
        }
    }
}
