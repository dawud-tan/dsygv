package griyasakha.ejmlstandin

/** Minimal stand-in matching the specific org.ejml.simple.SimpleMatrix
 * methods used by GuyanReduction.kt -- same names/signatures, so swapping
 * the import for the real EJML dependency in the Android project requires
 * no logic changes here, only removing this file and changing one import
 * line in GuyanReduction.kt. solve() uses Cholesky (K_ss is a principal
 * submatrix of the overall positive-definite K, hence itself positive
 * definite -- a standard linear algebra fact, and confirmed empirically
 * for this specific model by the whole-K Cholesky check already run).
 */
class SimpleMatrix {
    val rows: Int
    val cols: Int
    private val d: Array<DoubleArray>

    constructor(rows: Int, cols: Int) {
        this.rows = rows; this.cols = cols
        d = Array(rows) { DoubleArray(cols) }
    }

    constructor(data: Array<DoubleArray>) {
        rows = data.size; cols = if (data.isEmpty()) 0 else data[0].size
        d = data
    }

    fun get(i: Int, j: Int) = d[i][j]
    fun set(i: Int, j: Int, v: Double) {
        d[i][j] = v
    }

    fun mult(other: SimpleMatrix): SimpleMatrix {
        require(cols == other.rows) { "dimension mismatch in mult" }
        val out = SimpleMatrix(rows, other.cols)
        for (i in 0 until rows) for (j in 0 until other.cols) {
            var s = 0.0
            for (k in 0 until cols) s += d[i][k] * other.d[k][j]
            out.d[i][j] = s
        }
        return out
    }

    fun minus(other: SimpleMatrix): SimpleMatrix {
        require(rows == other.rows && cols == other.cols)
        val out = SimpleMatrix(rows, cols)
        for (i in 0 until rows) for (j in 0 until cols) out.d[i][j] = d[i][j] - other.d[i][j]
        return out
    }

    fun transpose(): SimpleMatrix {
        val out = SimpleMatrix(cols, rows)
        for (i in 0 until rows) for (j in 0 until cols) out.d[j][i] = d[i][j]
        return out
    }

    /** Solves this * X = b for X, via Cholesky (this must be symmetric
     * positive definite -- true here since it's always called on K_ss,
     * a principal submatrix of the overall positive-definite K). */
    fun solve(b: SimpleMatrix): SimpleMatrix {
        require(rows == cols) { "solve() requires a square matrix" }
        val n = rows
        val L = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = d[i][j]
            for (k in 0 until j) s -= L[i][k] * L[j][k]
            if (i == j) {
                require(s > 0.0) { "matrix not positive definite at row $i -- K_ss should never fail this for a properly-assembled model" }
                L[i][j] = kotlin.math.sqrt(s)
            } else {
                L[i][j] = s / L[j][j]
            }
        }
        // Solve L*Y=b (forward), then L^T*X=Y (backward), one column of b at a time
        val out = SimpleMatrix(n, b.cols)
        for (col in 0 until b.cols) {
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var s = b.d[i][col]
                for (k in 0 until i) s -= L[i][k] * y[k]
                y[i] = s / L[i][i]
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var s = y[i]
                for (k in i + 1 until n) s -= L[k][i] * x[k]
                x[i] = s / L[i][i]
            }
            for (i in 0 until n) out.d[i][col] = x[i]
        }
        return out
    }

    fun toArray2D(): Array<DoubleArray> = Array(rows) { i -> DoubleArray(cols) { j -> d[i][j] } }
}