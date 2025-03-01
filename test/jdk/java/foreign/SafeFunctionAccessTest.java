/*
 *  Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *  Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2022, 2022 All Rights Reserved
 * ===========================================================================
 */

/*
 * @test
 * @enablePreview
 * @requires ((os.arch == "amd64" | os.arch == "x86_64") & sun.arch.data.model == "64") | os.arch == "aarch64" | os.arch == "riscv64"
 * | os.arch == "ppc64" | os.arch == "ppc64le" | os.arch == "s390x"
 * @run testng/othervm --enable-native-access=ALL-UNNAMED SafeFunctionAccessTest
 */

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;

import java.lang.foreign.SegmentScope;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.foreign.VaList;
import java.util.stream.Stream;

import org.testng.annotations.*;

import static org.testng.Assert.*;

public class SafeFunctionAccessTest extends NativeTestHelper {
    static {
        System.loadLibrary("SafeAccess");
    }

    static MemoryLayout POINT = MemoryLayout.structLayout(
            C_INT, C_INT
    );

    @Test(expectedExceptions = IllegalStateException.class)
    public void testClosedStruct() throws Throwable {
        MemorySegment segment;
        try (Arena arena = Arena.openConfined()) {
            segment = arena.allocate(POINT);
        }
        assertFalse(segment.scope().isAlive());
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("struct_func"),
                FunctionDescriptor.ofVoid(POINT));

        handle.invokeExact(segment);
    }

    @Test
    public void testClosedStructAddr_6() throws Throwable {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func_6"),
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER));
        record Allocation(Arena drop, MemorySegment segment) {
            static Allocation of(MemoryLayout layout) {
                Arena arena = Arena.openShared();
                return new Allocation(arena, arena.allocate(layout));
            }
        }
        for (int i = 0 ; i < 6 ; i++) {
            Allocation[] allocations = new Allocation[]{
                    Allocation.of(POINT),
                    Allocation.of(POINT),
                    Allocation.of(POINT),
                    Allocation.of(POINT),
                    Allocation.of(POINT),
                    Allocation.of(POINT)
            };
            // check liveness
            allocations[i].drop().close();
            for (int j = 0 ; j < 6 ; j++) {
                if (i == j) {
                    assertFalse(allocations[j].drop().scope().isAlive());
                } else {
                    assertTrue(allocations[j].drop().scope().isAlive());
                }
            }
            try {
                handle.invokeWithArguments(Stream.of(allocations).map(Allocation::segment).toArray());
                fail();
            } catch (IllegalStateException ex) {
                assertTrue(ex.getMessage().contains("Already closed"));
            }
            for (int j = 0 ; j < 6 ; j++) {
                if (i != j) {
                    allocations[j].drop().close(); // should succeed!
                }
            }
        }
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testClosedVaList() throws Throwable {
        VaList list;
        try (Arena arena = Arena.openConfined()) {
            list = VaList.make(b -> b.addVarg(C_INT, 42), arena.scope());
        }
        assertFalse(list.segment().scope().isAlive());
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func"),
                FunctionDescriptor.ofVoid(C_POINTER));

        handle.invokeExact(list.segment());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testClosedUpcall() throws Throwable {
        MemorySegment upcall;
        try (Arena arena = Arena.openConfined()) {
            MethodHandle dummy = MethodHandles.lookup().findStatic(SafeFunctionAccessTest.class, "dummy", MethodType.methodType(void.class));
            upcall = Linker.nativeLinker().upcallStub(dummy, FunctionDescriptor.ofVoid(), arena.scope());
        }
        assertFalse(upcall.scope().isAlive());
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func"),
                FunctionDescriptor.ofVoid(C_POINTER));

        handle.invokeExact(upcall);
    }

    static void dummy() { }

    @Test
    public void testClosedVaListCallback() throws Throwable {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func_cb"),
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER));

        try (Arena arena = Arena.openConfined()) {
            VaList list = VaList.make(b -> b.addVarg(C_INT, 42), arena.scope());
            handle.invokeExact(list.segment(), sessionChecker(arena));
        }
    }

    @Test
    public void testClosedStructCallback() throws Throwable {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func_cb"),
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER));

        try (Arena arena = Arena.openConfined()) {
            MemorySegment segment = arena.allocate(POINT);
            handle.invokeExact(segment, sessionChecker(arena));
        }
    }

    @Test
    public void testClosedUpcallCallback() throws Throwable {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                findNativeOrThrow("addr_func_cb"),
                FunctionDescriptor.ofVoid(C_POINTER, C_POINTER));

        try (Arena arena = Arena.openConfined()) {
            MethodHandle dummy = MethodHandles.lookup().findStatic(SafeFunctionAccessTest.class, "dummy", MethodType.methodType(void.class));
            MemorySegment upcall = Linker.nativeLinker().upcallStub(dummy, FunctionDescriptor.ofVoid(), arena.scope());
            handle.invokeExact(upcall, sessionChecker(arena));
        }
    }

    MemorySegment sessionChecker(Arena arena) {
        try {
            MethodHandle handle = MethodHandles.lookup().findStatic(SafeFunctionAccessTest.class, "checkSession",
                    MethodType.methodType(void.class, Arena.class));
            handle = handle.bindTo(arena);
            return Linker.nativeLinker().upcallStub(handle, FunctionDescriptor.ofVoid(), SegmentScope.auto());
        } catch (Throwable ex) {
            throw new AssertionError(ex);
        }
    }

    static void checkSession(Arena arena) {
        try {
            arena.close();
            fail("Session closed unexpectedly!");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("acquired")); //if acquired, fine
        }
    }
}
