/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime.layouts.rubinius;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import org.jruby.truffle.om.dsl.api.Layout;
import org.jruby.truffle.om.dsl.api.Volatile;
import org.jruby.truffle.runtime.layouts.BasicObjectLayout;

@Layout
public interface AtomicReferenceLayout extends BasicObjectLayout {

    DynamicObjectFactory createAtomicReferenceShape(DynamicObject logicalClass,
                                                    DynamicObject metaClass);

    DynamicObject createAtomicReference(DynamicObjectFactory factory,
                                        @Volatile Object value);

    Object getValue(DynamicObject object);
    void setValue(DynamicObject object, Object value);
    boolean compareAndSetValue(DynamicObject object, Object expected_value, Object value);
    Object getAndSetValue(DynamicObject object, Object value);

}
