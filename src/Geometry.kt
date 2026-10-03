package griyasakha

/** A structural node in 3D space, with its own global DOF indices assigned
 * once its role (fixed base vs. active roof) is known. */
data class Node(
    val id: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val isFixedBase: Boolean
) {
    // Global DOF indices in the FULL (pre-Guyan-reduction) free-DOF system,
    // 6 per active node: [Ux, Uy, Uz, Rx, Ry, Rz]. Fixed-base nodes get no
    // DOF at all -- they're prescribed (zero), not part of the free system,
    // so they never appear here.
    var dofStart: Int = -1 // index of Ux; Uy=dofStart+1, ..., Rz=dofStart+5

    val hasDof: Boolean get() = !isFixedBase
}

/** DOF per node in the FULL system, and how many of those are Guyan masters.
 * These two numbers are a CONTRACT between three places that must agree:
 * GeometryBuilder.build() (which strides dofStart by DOF_PER_NODE),
 * GuyanReduction.reduce() (which picks the first MASTER_DOF_PER_NODE of each
 * block), and any code mapping a full-system node onto the reduced layout.
 * They were previously bare literals at every call site plus a hand-inlined
 * `dofStart / 6 * 2` in CodeChecks.kt -- correct, but silently wrong the
 * moment anyone changed one of them without finding all the others. */
const val DOF_PER_NODE = 6
const val MASTER_DOF_PER_NODE = 2

/** Index of this node's Ux in the Guyan-reduced [Ux, Uy, Ux, Uy, ...] layout.
 * Valid because reduce() walks nodes in the same order build() assigned
 * dofStart, so node ordinal = dofStart / DOF_PER_NODE.
 *
 * The require() is load-bearing, not decoration: a fixed base node has
 * dofStart = -1, and Kotlin integer division truncates toward zero, so
 * -1 / 6 * 2 evaluates to 0 -- silently aliasing every base node onto the
 * FIRST roof node's Ux instead of failing. Any caller that iterates all 24
 * nodes rather than just the 12 with DOF would read plausible-looking wrong
 * numbers. Fail loudly instead. */
val Node.reducedUx: Int
    get() {
        require(hasDof) { "node $id is a fixed base node: it has no reduced DOF" }
        return dofStart / DOF_PER_NODE * MASTER_DOF_PER_NODE
    }

/** Index of this node's Uy in the reduced layout. */
val Node.reducedUy: Int get() = reducedUx + 1

data class Grid(
    val xLines: List<Double>,
    val yLines: List<Double>,
    val zBase: Double,
    val zRoof: Double
)

object GeometryBuilder {
    /** Builds the 12 base + 12 roof node grid per Section 5 of the spec,
     * and assigns global DOF indices to every non-fixed (roof) node.
     * Returns nodes plus the total free-DOF count of the FULL (un-reduced)
     * system, i.e. before Guyan reduction -- 12 roof nodes x 6 DOF = 72.
     */
    fun build(grid: Grid): Pair<List<Node>, Int> {
        val nodes = mutableListOf<Node>()
        var id = 0
        // Base nodes first (Z = zBase), fully fixed -- no DOF assigned.
        for (y in grid.yLines) for (x in grid.xLines) {
            nodes.add(Node(id++, x, y, grid.zBase, isFixedBase = true))
        }
        // Roof nodes (Z = zRoof), active.
        var dofCursor = 0
        for (y in grid.yLines) for (x in grid.xLines) {
            val n = Node(id++, x, y, grid.zRoof, isFixedBase = false)
            n.dofStart = dofCursor
            dofCursor += 6
            nodes.add(n)
        }
        return Pair(nodes, dofCursor)
    }

    fun findNode(nodes: List<Node>, x: Double, y: Double, z: Double, tol: Double = 1e-6): Node {
        return nodes.first {
            kotlin.math.abs(it.x - x) < tol && kotlin.math.abs(it.y - y) < tol && kotlin.math.abs(
                it.z - z
            ) < tol
        }
    }
}

fun main() {
    val grid = Grid(
        xLines = listOf(0.0, 3.0, 6.0),
        yLines = listOf(0.0, 3.5, 7.0, 10.3),
        zBase = 0.0,
        zRoof = 3.5
    )
    val (nodes, fullDofCount) = GeometryBuilder.build(grid)
    println("Total nodes: ${nodes.size} (expected 24)")
    println("Base nodes (fixed): ${nodes.count { it.isFixedBase }} (expected 12)")
    println("Roof nodes (active): ${nodes.count { !it.isFixedBase }} (expected 12)")
    println("Full (pre-Guyan) free-DOF count: $fullDofCount (expected 72 = 12 roof nodes x 6 DOF)")

    // Sanity: every roof node got a distinct, contiguous dofStart
    val roofNodes = nodes.filter { !it.isFixedBase }.sortedBy { it.dofStart }
    val expectedStarts = (0 until roofNodes.size).map { it * 6 }
    println("DOF starts contiguous & correct: ${roofNodes.map { it.dofStart } == expectedStarts}")

    // Sanity: can find the four torsional-irregularity corner nodes referenced in the spec
    val corners = listOf(
        Triple(0.0, 0.0, 3.5), Triple(6.0, 0.0, 3.5),
        Triple(0.0, 10.3, 3.5), Triple(6.0, 10.3, 3.5)
    )
    for ((x, y, z) in corners) {
        val n = GeometryBuilder.findNode(nodes, x, y, z)
        println("  corner ($x, $y, $z) -> node id=${n.id}, dofStart=${n.dofStart}")
    }
}