/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime.subsystems;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.ConditionProfile;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.RubyString;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.RubySyntaxTag;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.loader.SourceLoader;
import org.jruby.util.StringSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TraceManager {

    private final RubyContext context;

    private Collection<Instrument> instruments;
    private boolean isInTraceFunc = false;
    private final Map<SyntaxTag, AdvancedInstrumentRootFactory> eventFactories = new HashMap<>();

    public TraceManager(RubyContext context) {
        this.context = context;
    }

    public void setTraceFunc(final DynamicObject traceFunc) {
        assert RubyGuards.isRubyProc(traceFunc);

        if (instruments != null) {
            for (Instrument instrument : instruments) {
                instrument.dispose();
            }
        }

        if (traceFunc == null) {
            instruments = null;
            return;
        }

        final AdvancedInstrumentResultListener listener = new AdvancedInstrumentResultListener() {

            @Override
            public void notifyResult(Node node, VirtualFrame virtualFrame, Object o) {
            }

            @Override
            public void notifyFailure(Node node, VirtualFrame virtualFrame, RuntimeException e) {
            }

        };

        final AdvancedInstrumentRootFactory lineEventFactory = new AdvancedInstrumentRootFactory() {

            @Override
            public AdvancedInstrumentRoot createInstrumentRoot(Probe probe, Node node) {
                final DynamicObject event = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist("line", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);

                final SourceSection sourceSection = node.getEncapsulatingSourceSection();

                final DynamicObject file = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist(sourceSection.getSource().getName(), UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);
                final int line = sourceSection.getStartLine();

                return new BaseAdvancedIntrumentRoot(traceFunc, event, file, line);
            }

        };

        final AdvancedInstrumentRootFactory callEventFactory = new AdvancedInstrumentRootFactory() {

            @Override
            public AdvancedInstrumentRoot createInstrumentRoot(Probe probe, Node node) {
                final DynamicObject event = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist("call", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);

                return new AdvancedInstrumentRoot() {

                    @Child private DirectCallNode callNode;

                    private final ConditionProfile inTraceFuncProfile = ConditionProfile.createBinaryProfile();

                    @Override
                    public Object executeRoot(Node node, VirtualFrame frame) {
                        if (!inTraceFuncProfile.profile(isInTraceFunc)) {
                            // set_trace_func reports the file and line of the call site.
                            final SourceSection sourceSection = Truffle.getRuntime().getCallerFrame().getCallNode().getEncapsulatingSourceSection();
                            final String filename = sourceSection.getSource().getName();
                            final DynamicObject file = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist(filename, UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);

                            if (!context.getOptions().INCLUDE_CORE_FILE_CALLERS_IN_SET_TRACE_FUNC && filename.startsWith(SourceLoader.TRUFFLE_SCHEME)) {
                                return context.getCoreLibrary().getNilObject();
                            }

                            final int line = sourceSection.getStartLine();

                            final Object self = RubyArguments.getSelf(frame.getArguments());
                            final Object classname = context.getCoreLibrary().getLogicalClass(self);
                            final Object id = context.getSymbol(RubyArguments.getMethod(frame.getArguments()).getName());

                            final DynamicObject binding = Layouts.BINDING.createBinding(
                                    context.getCoreLibrary().getBindingFactory(),
                                    frame.materialize());

                            if (callNode == null) {
                                CompilerDirectives.transferToInterpreterAndInvalidate();

                                callNode = insert(Truffle.getRuntime().createDirectCallNode(Layouts.PROC.getCallTargetForType(traceFunc)));

                                if (callNode.isCallTargetCloningAllowed()) {
                                    callNode.cloneCallTarget();
                                }

                                if (callNode.isInlinable()) {
                                    callNode.forceInlining();
                                }
                            }

                            isInTraceFunc = true;

                            callNode.call(frame, RubyArguments.pack(
                                    Layouts.PROC.getMethod(traceFunc),
                                    Layouts.PROC.getDeclarationFrame(traceFunc),
                                    Layouts.PROC.getSelf(traceFunc),
                                    Layouts.PROC.getBlock(traceFunc),
                                    new Object[]{event, file, line, id, binding, classname}));

                            isInTraceFunc = false;
                        }

                        return null;
                    }

                    @Override
                    public String instrumentationInfo() {
                        return "set_trace_func";
                    }

                };
            }

        };

        final AdvancedInstrumentRootFactory classEventFactory = new AdvancedInstrumentRootFactory() {

            @Override
            public AdvancedInstrumentRoot createInstrumentRoot(Probe probe, Node node) {
                final DynamicObject event = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist("class", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);

                final SourceSection sourceSection = node.getEncapsulatingSourceSection();

                final DynamicObject file = Layouts.STRING.createString(context.getCoreLibrary().getStringFactory(), RubyString.encodeBytelist(sourceSection.getSource().getName(), UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null);
                final int line = sourceSection.getStartLine();

                return new BaseAdvancedIntrumentRoot(traceFunc, event, file, line);
            }

        };

        eventFactories.put(RubySyntaxTag.LINE, lineEventFactory);
        eventFactories.put(RubySyntaxTag.CALL, callEventFactory);
        eventFactories.put(RubySyntaxTag.CLASS, classEventFactory);

        instruments = new ArrayList<>();

        for (Map.Entry<SyntaxTag, AdvancedInstrumentRootFactory> entry : eventFactories.entrySet()) {
            for (Probe probe : Probe.findProbesTaggedAs(entry.getKey())) {
                final Instrument instrument = Instrument.create(listener, entry.getValue(), null, "set_trace_func");
                instruments.add(instrument);
                probe.attach(instrument);
            }
        }

        Probe.addProbeListener(new ProbeListener() {
            @Override
            public void startASTProbing(Source source) {
            }

            @Override
            public void newProbeInserted(Probe probe) {
            }

            @Override
            public void probeTaggedAs(Probe probe, SyntaxTag tag, Object tagValue) {
                if (eventFactories.containsKey(tag)) {
                    final Instrument instrument = Instrument.create(listener, eventFactories.get(tag), null, "set_trace_func");
                    instruments.add(instrument);
                    probe.attach(instrument);
                }
            }

            @Override
            public void endASTProbing(Source source) {
            }
        });
    }

    private final class BaseAdvancedIntrumentRoot extends AdvancedInstrumentRoot {
        @Child private DirectCallNode callNode;

        private final ConditionProfile inTraceFuncProfile = ConditionProfile.createBinaryProfile();

        private final DynamicObject traceFunc;
        private final Object event;
        private final Object file;
        private final int line;

        public BaseAdvancedIntrumentRoot(DynamicObject traceFunc, Object event, Object file, int line) {
            this.traceFunc = traceFunc;
            this.event = event;
            this.file = file;
            this.line = line;
        }

        @Override
        public Object executeRoot(Node node, VirtualFrame frame) {
            if (!inTraceFuncProfile.profile(isInTraceFunc)) {
                final Object self = context.getCoreLibrary().getNilObject();
                final Object classname = context.getCoreLibrary().getNilObject();
                final Object id = context.getCoreLibrary().getNilObject();

                final DynamicObject binding = Layouts.BINDING.createBinding(
                        context.getCoreLibrary().getBindingFactory(),
                        frame.materialize());

                if (callNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();

                    callNode = insert(Truffle.getRuntime().createDirectCallNode(Layouts.PROC.getCallTargetForType(traceFunc)));

                    if (callNode.isCallTargetCloningAllowed()) {
                        callNode.cloneCallTarget();
                    }

                    if (callNode.isInlinable()) {
                        callNode.forceInlining();
                    }
                }

                isInTraceFunc = true;

                callNode.call(frame, RubyArguments.pack(
                        Layouts.PROC.getMethod(traceFunc),
                        Layouts.PROC.getDeclarationFrame(traceFunc),
                        Layouts.PROC.getSelf(traceFunc),
                        Layouts.PROC.getBlock(traceFunc),
                        new Object[]{event, file, line, id, binding, classname}));

                isInTraceFunc = false;
            }

            return null;
        }

        @Override
        public String instrumentationInfo() {
            return "set_trace_func";
        }
    }

}
