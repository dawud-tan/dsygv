package com.dawud.mpmrbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import griyasakha.Assembler
import griyasakha.DOF_PER_NODE
import griyasakha.EigensystemResult
import griyasakha.GeometryBuilder
import griyasakha.Grid
import griyasakha.GuyanReduction
import griyasakha.MASTER_DOF_PER_NODE
import griyasakha.matVec
import griyasakha.runAllChecks
import griyasakha.solveAllModesForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class DeviceVerificationTest {

    @Test
    fun harnessPassesOnDevice() {
        // runAllChecks() returns a count instead of calling exitProcess,
        // which would kill the instrumented test runner.
        assertEquals("verification harness reported failures on device", 0, runAllChecks())
    }

    @Test
    fun nativeLibraryLoads() {
        assertTrue(
            "libmpmreigensolver.so failed to load -- check abiFilters " +
                    "includes arm64-v8a and that CMake actually built it.",
            EigensolverBridge.ensureLoaded()
        )
    }

    @Test
    fun nativeSolverMatchesJvmReference() {
        assertTrue(EigensolverBridge.ensureLoaded())
        val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
        val (nodes, fullDof) = GeometryBuilder.build(grid)
        val asm = Assembler.assemble(grid, nodes, fullDof)
        Assembler.assignMass(asm, grid, nodes)
        val red = GuyanReduction.reduce(asm.K, asm.M, DOF_PER_NODE, MASTER_DOF_PER_NODE)

        val native = EigensolverBridge.solve(red.kReduced, red.mReduced)
        assertEquals("mode count", red.kReduced.size, native.modes.size)

        // 1. Residual -- the real acceptance test. Does not depend on the
        //    JVM reference being correct.
        for ((i, m) in native.modes.withIndex()) {
            val kp = matVec(red.kReduced, m.phi)
            var rn = 0.0;
            var kn = 0.0
            for (j in kp.indices) {
                val r = kp[j] - m.omega2 * red.mReduced[j] * m.phi[j]
                rn += r * r; kn += kp[j] * kp[j]
            }
            assertTrue("mode $i residual ${sqrt(rn) / sqrt(kn)}", sqrt(rn) / sqrt(kn) < 1e-8)
        }

        // 2. Mass normalization phi^T M phi = 1. Every downstream formula
        //    drops its denominator on this basis, so a convention change
        //    would be a silent-wrong-answer bug, not a crash.
        for ((i, m) in native.modes.withIndex()) {
            var norm = 0.0
            for (j in m.phi.indices) norm += m.phi[j] * red.mReduced[j] * m.phi[j]
            assertEquals("mode $i not mass-normalized", 1.0, norm, 1e-9)
        }

        // 3. Eigenvalues vs JVM reference. Compare VALUES only -- eigenvectors
        //    differ by sign and by arbitrary rotation within degenerate
        //    subspaces, so elementwise comparison would fail spuriously.
        val reference = solveAllModesForTest(red.kReduced, red.mReduced)
        for (i in native.modes.indices) {
            assertEquals(
                "eigenvalue $i", reference[i].omega2, native.modes[i].omega2,
                1e-6 * maxOf(abs(reference[i].omega2), 1.0)
            )
        }
    }

    @Test
    fun rejectedInputSurfacesAsException() {
        // Native returns an empty array on bad input; unpack() must raise
        // rather than yield an empty mode list. This covers the KOTLIN half
        // of the contract only -- see nativeRejectsMismatchedArrays() for the
        // half that actually crosses JNI.
        try {
            EigensystemResult.unpack(DoubleArray(0), 24)
            fail("expected an exception for rejected input")
        } catch (e: IllegalStateException) { /* expected */
        }
    }

    @Test
    fun nativeRejectsMismatchedArrays() {
        // Drives the real JNI boundary so the NATIVE length check fires.
        // unpack(DoubleArray(0), n) alone never leaves the JVM, so nothing
        // previously proved the C++ side rejects rather than reading past the
        // end of a short array -- a silent-wrong-answer risk, not a crash.
        assertTrue(EigensolverBridge.ensureLoaded())
        val short9 = DoubleArray(9) // sized for n=3, but we claim n=4
        val raw = EigensolverBridge.solveEigensystemRaw(short9, short9, 4)
        assertEquals("native must signal rejection with an empty array", 0, raw.size)

        // n <= 0 is the other rejection branch.
        assertEquals(
            "n<=0 must also be rejected", 0,
            EigensolverBridge.solveEigensystemRaw(DoubleArray(0), DoubleArray(0), 0).size
        )

        // And the empty return must surface as an exception, not zero modes.
        try {
            EigensystemResult.unpack(raw, 4)
            fail("expected unpack() to reject the native empty array")
        } catch (e: IllegalStateException) { /* expected */
        }
    }

    @Test
    fun massVectorLengthMismatchIsCaught() {
        // Guards the precondition in EigensolverBridge.solve(): a short mass
        // vector used to reach the native side and come back as a confusing
        // "size mismatch" naming the symptom instead of the cause.
        val k = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        try {
            EigensolverBridge.solve(k, DoubleArray(2))
            fail("expected an exception for a mismatched mass vector")
        } catch (e: IllegalArgumentException) { /* expected */
        }
    }
}