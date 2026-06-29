package com.paperleaf.sketchbook.pageflip.premium.mesh

import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.max

class MeshGenerator {

    companion object {
        private const val TAG = "MeshGenerator"
        private const val DEFAULT_GRID_SIZE = 30
        private const val MAX_GRID_SIZE = 60
        private const val MIN_GRID_SIZE = 20

        private const val VERTEX_STRIDE = 8
        private const val POSITION_COMPONENT_COUNT = 3
        private const val NORMAL_COMPONENT_COUNT = 3
        private const val TEX_COORD_COMPONENT_COUNT = 2

        private const val CURL_RADIUS_FACTOR = 0.12f
        private const val ADAPTIVE_CONCENTRATION = 3.0f
    }

    data class MeshData(
        val vertices: FloatArray,
        val indices: ShortArray,
        val vertexCount: Int,
        val indexCount: Int,
        val gridSize: Int
    )

    fun generatePlaneMesh(
        width: Float = 1.0f,
        height: Float = 1.0f,
        gridSize: Int = DEFAULT_GRID_SIZE
    ): MeshData {
        val clampedGridSize = gridSize.coerceIn(MIN_GRID_SIZE, MAX_GRID_SIZE)
        val vertexCount = (clampedGridSize + 1) * (clampedGridSize + 1)
        val indexCount = clampedGridSize * clampedGridSize * 6

        val vertices = FloatArray(vertexCount * VERTEX_STRIDE)
        val indices = ShortArray(indexCount)

        val halfWidth = width / 2.0f
        val halfHeight = height / 2.0f

        val xPositions = adaptiveGrid(clampedGridSize, -halfWidth, halfWidth)
        val yPositions = adaptiveGrid(clampedGridSize, -halfHeight, halfHeight)

        var vertexIndex = 0
        for (row in 0..clampedGridSize) {
            for (col in 0..clampedGridSize) {
                val x = xPositions[col]
                val y = yPositions[row]

                vertices[vertexIndex++] = x
                vertices[vertexIndex++] = y
                vertices[vertexIndex++] = 0.0f

                vertices[vertexIndex++] = 0.0f
                vertices[vertexIndex++] = 0.0f
                vertices[vertexIndex++] = 1.0f

                val u = col.toFloat() / clampedGridSize
                val v = row.toFloat() / clampedGridSize
                vertices[vertexIndex++] = u
                vertices[vertexIndex++] = v
            }
        }

        var indexIndex = 0
        for (row in 0 until clampedGridSize) {
            for (col in 0 until clampedGridSize) {
                val topLeft = row * (clampedGridSize + 1) + col
                val topRight = topLeft + 1
                val bottomLeft = (row + 1) * (clampedGridSize + 1) + col
                val bottomRight = bottomLeft + 1

                indices[indexIndex++] = topLeft.toShort()
                indices[indexIndex++] = bottomLeft.toShort()
                indices[indexIndex++] = topRight.toShort()

                indices[indexIndex++] = topRight.toShort()
                indices[indexIndex++] = bottomLeft.toShort()
                indices[indexIndex++] = bottomRight.toShort()
            }
        }

        return MeshData(vertices, indices, vertexCount, indexCount, clampedGridSize)
    }

    fun applyCurlDeformation(
        meshData: MeshData,
        curlAngle: Float,
        curlRadius: Float,
        axisX: Float,
        axisAngle: Float = 0f,
        outVertices: FloatArray? = null
    ): FloatArray {
        Log.d("TRACE_MeshGen", "applyCurlDeformation entered: vertices=${meshData.vertices.size} grid=${meshData.gridSize} angle=$curlAngle radius=$curlRadius axisX=$axisX axisAngle=$axisAngle")
        val vertices: FloatArray
        if (outVertices != null && outVertices.size >= meshData.vertices.size) {
            System.arraycopy(meshData.vertices, 0, outVertices, 0, meshData.vertices.size)
            vertices = outVertices
        } else {
            vertices = meshData.vertices.clone()
        }
        val gridSize = meshData.gridSize

        val effectiveAngle = curlAngle.coerceIn(0.001f, Math.PI.toFloat())
        val sinA = sin(axisAngle)
        val cosA = cos(axisAngle)

        var vertexIndex = 0
        for (row in 0..gridSize) {
            for (col in 0..gridSize) {
                val baseIndex = vertexIndex
                val vx = vertices[baseIndex]
                val vy = vertices[baseIndex + 1]

                val dx = vx - axisX
                val dy = vy
                val t = dx * sinA + dy * cosA
                val d = dx * cosA - dy * sinA

                if (d > 0f) {
                    val maxArc = curlRadius * effectiveAngle
                    val closestX = axisX + t * sinA
                    val closestY = t * cosA

                    if (d <= maxArc) {
                        val theta = d / curlRadius
                        val sinT = sin(theta)
                        val cosT = cos(theta)

                        vertices[baseIndex] = closestX + curlRadius * sinT * cosA
                        vertices[baseIndex + 1] = closestY - curlRadius * sinT * sinA
                        vertices[baseIndex + 2] = curlRadius * (1f - cosT)
                    } else {
                        val extra = d - maxArc
                        val baseOffset = curlRadius * sin(effectiveAngle)
                        val baseLift = curlRadius * (1f - cos(effectiveAngle))
                        val offset = baseOffset + extra * cos(effectiveAngle)
                        val lift = baseLift + extra * sin(effectiveAngle)

                        vertices[baseIndex] = closestX + offset * cosA
                        vertices[baseIndex + 1] = closestY - offset * sinA
                        vertices[baseIndex + 2] = lift
                    }
                } else {
                    vertices[baseIndex + 2] = 0f
                }

                vertexIndex += VERTEX_STRIDE
            }
        }

        calculateNormalsFiniteDifference(vertices, gridSize)

        return vertices
    }

    fun applyComplexDeformation(
        meshData: MeshData,
        params: DeformationParams
    ): FloatArray {
        val vertices = meshData.vertices.clone()
        val gridSize = meshData.gridSize

        val halfWidth = computeHalfWidth(vertices, gridSize)
        val halfHeight = computeHalfHeight(vertices, gridSize)
        val pageExtent = 2f * halfWidth
        val curlRadius = pageExtent * CURL_RADIUS_FACTOR * params.curlIntensity.coerceIn(0.5f, 2.0f)
        val curlAngle = params.curlFactor * Math.PI.toFloat()
        val foldOrigin = halfWidth * (1f - 2f * params.curlPosition)

        var vertexIndex = 0
        for (row in 0..gridSize) {
            for (col in 0..gridSize) {
                val baseIndex = vertexIndex
                val x = vertices[baseIndex]
                val y = vertices[baseIndex + 1]

                val d = x - foldOrigin

                if (d > 0f) {
                    val maxArc = curlRadius * curlAngle
                    val displacement: Float
                    val lift: Float

                    if (d <= maxArc) {
                        val alpha = d / curlRadius
                        displacement = curlRadius * sin(alpha)
                        lift = curlRadius * (1f - cos(alpha))
                    } else {
                        val extra = d - maxArc
                        displacement = curlRadius * sin(curlAngle) + extra * cos(curlAngle)
                        lift = curlRadius * (1f - cos(curlAngle)) + extra * sin(curlAngle)
                    }

                    var finalLift = lift

                    if (params.enableWaveEffect) {
                        finalLift += sin(x * params.waveFrequency) *
                                     cos(y * params.waveFrequency) *
                                     params.waveAmplitude
                    }

                    if (params.enableEdgeSoftness) {
                        finalLift *= calculateEdgeSoftness(x, y, params)
                    }

                    vertices[baseIndex] = foldOrigin + displacement
                    vertices[baseIndex + 2] = finalLift
                } else {
                    vertices[baseIndex + 2] = 0f
                }

                vertexIndex += VERTEX_STRIDE
            }
        }

        calculateNormalsFiniteDifference(vertices, gridSize)

        return vertices
    }

    private fun calculateEdgeSoftness(x: Float, y: Float, params: DeformationParams): Float {
        if (!params.enableEdgeSoftness) return 1.0f

        val edgeDist = minOf(
            (x + 0.5f) / params.edgeSoftnessWidth,
            (0.5f - x) / params.edgeSoftnessWidth,
            (y + 0.5f) / params.edgeSoftnessWidth,
            (0.5f - y) / params.edgeSoftnessWidth
        )

        return 1.0f - (1.0f - edgeDist.coerceIn(0f, 1f)) * params.edgeSoftnessStrength
    }

    private fun calculateNormalsFiniteDifference(vertices: FloatArray, gridSize: Int) {
        for (row in 0..gridSize) {
            for (col in 0..gridSize) {
                val idx = (row * (gridSize + 1) + col) * VERTEX_STRIDE

                val idxL = if (col > 0) idx - VERTEX_STRIDE else idx
                val idxR = if (col < gridSize) idx + VERTEX_STRIDE else idx
                val idxB = if (row > 0) idx - (gridSize + 1) * VERTEX_STRIDE else idx
                val idxT = if (row < gridSize) idx + (gridSize + 1) * VERTEX_STRIDE else idx

                val dx = vertices[idxR] - vertices[idxL]
                val dy = vertices[idxT + 1] - vertices[idxB + 1]
                val dzx = vertices[idxR + 2] - vertices[idxL + 2]
                val dzy = vertices[idxT + 2] - vertices[idxB + 2]

                val tx = dx
                val ty = 0f
                val tz = dzx

                val ux = 0f
                val uy = dy
                val uz = dzy

                val nx = ty * uz - tz * uy
                val ny = tz * ux - tx * uz
                val nz = tx * uy - ty * ux

                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-6f) {
                    vertices[idx + 3] = nx / len
                    vertices[idx + 4] = ny / len
                    vertices[idx + 5] = nz / len
                } else {
                    vertices[idx + 3] = 0f
                    vertices[idx + 4] = 0f
                    vertices[idx + 5] = 1f
                }
            }
        }
    }

    private fun adaptiveGrid(size: Int, minVal: Float, maxVal: Float): FloatArray {
        val positions = FloatArray(size + 1)
        for (i in 0..size) {
            val t = i.toFloat() / size
            val diff = t - 0.5f
            val mappedT = 0.5f + diff / (1f + ADAPTIVE_CONCENTRATION.toFloat() *
                exp(-diff * diff * 30f))
            positions[i] = minVal + mappedT * (maxVal - minVal)
        }
        return positions
    }

    private fun computeHalfWidth(vertices: FloatArray, gridSize: Int): Float {
        val minX = vertices[0]
        val maxX = vertices[gridSize * VERTEX_STRIDE]
        return (maxX - minX) / 2f
    }

    private fun computeHalfHeight(vertices: FloatArray, gridSize: Int): Float {
        val minY = vertices[1]
        val maxY = vertices[(gridSize * (gridSize + 1)) * VERTEX_STRIDE + 1]
        return (maxY - minY) / 2f
    }

    data class DeformationParams(
        val curlFactor: Float = 0.5f,
        val curlIntensity: Float = 0.2f,
        val curlPosition: Float = 0.5f,
        val enableWaveEffect: Boolean = true,
        val waveFrequency: Float = 10.0f,
        val waveAmplitude: Float = 0.01f,
        val enableEdgeSoftness: Boolean = true,
        val edgeSoftnessWidth: Float = 0.1f,
        val edgeSoftnessStrength: Float = 0.3f
    )
}
