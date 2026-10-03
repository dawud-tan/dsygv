package com.dawud.mpmrbench

import griyasakha.EigensolverSupport
import griyasakha.EigensystemResult
import griyasakha.Mat

/** JNI bridge to the verified Phase 1 C++ eigensolver.
 *
 * *** PACKAGE AND CLASS NAME ARE LOAD-BEARING -- DO NOT RENAME ***
 * The native library exports the symbol
 *     Java_com_dawud_mpmrbench_EigensolverBridge_solveEigensystemRaw
 * JNI derives that name mechanically from package + class + method. Moving
 * this object to another package, or renaming the class or the external
 * fun, breaks the link at RUNTIME with UnsatisfiedLinkError -- it compiles
 * fine, so nothing catches it until the app runs on the device. If you must
 * rename, change the C++ symbol to match in the same commit.
 *
 * (A Kotlin `object`'s external fun compiles to an instance method on the
 * singleton, but JNI name mangling is identical for static and instance
 * methods, so the existing C++ symbol binds either way.)
 */
object EigensolverBridge {
    @Volatile
    private var loaded = false

    /** Loads the native library, once. Returns false if unavailable, so
     * JVM-side tests can fall back instead of crashing. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("mpmreigensolver")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** `internal` rather than `private` so the instrumented tests can drive
     * the JNI boundary directly and prove the NATIVE size check fires --
     * unpack(DoubleArray(0), n) alone only exercises the Kotlin half of the
     * rejection contract.
     *
     * *** @JvmName IS LOAD-BEARING, DO NOT REMOVE ***
     * Kotlin mangles the JVM name of an `internal` member by appending
     * `$<module>`, so without this annotation the method compiles to
     * `solveEigensystemRaw$app` and JNI then looks for the symbol
     * `Java_com_dawud_mpmrbench_EigensolverBridge_solveEigensystemRaw_00024app`,
     * which the .so does not export -> UnsatisfiedLinkError at RUNTIME only.
     * Verified: dropping the annotation fails nativeSolverMatchesJvmReference
     * and nativeRejectsMismatchedArrays on the device, and nothing at compile
     * time. @JvmName pins the unmangled name the C++ symbol is derived from. */
    @JvmName("solveEigensystemRaw")
    internal external fun solveEigensystemRaw(
        flatK: DoubleArray,
        flatM: DoubleArray,
        n: Int
    ): DoubleArray

    /** Solves K.phi = lambda.M.phi on the device via the native solver.
     * Throws if the library isn't loadable -- callers wanting a fallback
     * should check ensureLoaded() first. */
    fun solve(kReduced: Mat, mReduced: DoubleArray): EigensystemResult {
        if (!ensureLoaded()) throw UnsatisfiedLinkError(
            "libmpmreigensolver.so not loaded. Check that CMake built it for " +
                    "arm64-v8a and that abiFilters includes that ABI."
        )
        val n = kReduced.size
        // Caught here rather than as a bare "size mismatch" from the native
        // side, which names the symptom instead of the cause.
        require(mReduced.size == n) {
            "mass vector length ${mReduced.size} does not match K dimension $n"
        }
        val raw = solveEigensystemRaw(
            EigensolverSupport.flatten(kReduced),
            EigensolverSupport.flattenDiagonalMass(mReduced),
            n
        )
        return EigensystemResult.unpack(raw, n)
    }
}