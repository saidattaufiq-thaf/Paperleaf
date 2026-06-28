package com.paperleaf.sketchbook.pageflip.premium.mesh

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates adaptive 3D mesh for realistic paper curl simulation.
 * Uses 30x30 grid minimum with subdivision for smooth deformation.
 * 
 * Features:
 * - High-density vertex grid for smooth curves
 * - Normal vectors for lighting calculations
 * - Texture coordinates mapping
 * - Dynamic vertex positioning for curl effect
 * - Object pooling for performance
 */
class MeshGenerator {
    
    companion object {
        private const val TAG = "MeshGenerator"
        private const val DEFAULT_GRID_SIZE = 30
        private const val MAX_GRID_SIZE = 60
        private const val MIN_GRID_SIZE = 20
        
        // Vertex stride: position (3) + normal (3) + texCoord (2) = 8 floats
        private const val VERTEX_STRIDE = 8
        private const val POSITION_COMPONENT_COUNT = 3
        private const val NORMAL_COMPONENT_COUNT = 3
        private const val TEX_COORD_COMPONENT_COUNT = 2
    }
    
    /**
     * Data class holding mesh vertex data
     */
    data class MeshData(
        val vertices: FloatArray,
        val indices: ShortArray,
        val vertexCount: Int,
        val indexCount: Int,
        val gridSize: Int
    )
    
    /**
     * Pooled mesh for reuse to reduce allocations
     */
    class PooledMesh(val meshData: MeshData) {
        val vertexBuffer: FloatBuffer
        val indexBuffer: ShortBuffer
        var isDirty = true
        
        init {
            // Allocate direct buffers for OpenGL
            val bbVertices = ByteBuffer.allocateDirect(meshData.vertices.size * 4)
                .order(ByteOrder.nativeOrder())
            vertexBuffer = bbVertices.asFloatBuffer()
            vertexBuffer.put(meshData.vertices)
            vertexBuffer.position(0)
            
            val bbIndices = ByteBuffer.allocateDirect(meshData.indices.size * 2)
                .order(ByteOrder.nativeOrder())
            indexBuffer = bbIndices.asShortBuffer()
            indexBuffer.put(meshData.indices)
            indexBuffer.position(0)
        }
        
        fun updateVertices(newVertices: FloatArray) {
            vertexBuffer.clear()
            vertexBuffer.put(newVertices)
            vertexBuffer.position(0)
            isDirty = true
        }
    }
    
    private val meshPool = mutableListOf<PooledMesh>()
    private val activeMeshes = mutableMapOf<Int, PooledMesh>()
    
    /**
     * Generate a base plane mesh with specified grid density
     * 
     * @param width Width of the page in OpenGL units
     * @param height Height of the page in OpenGL units
     * @param gridSize Number of subdivisions per axis (default 30)
     * @return MeshData containing vertices and indices
     */
    fun generatePlaneMesh(
        width: Float = 1.0f,
        height: Float = 1.0f,
        gridSize: Int = DEFAULT_GRID_SIZE
    ): MeshData {
        val clampedGridSize = gridSize.coerceIn(MIN_GRID_SIZE, MAX_GRID_SIZE)
        val vertexCount = (clampedGridSize + 1) * (clampedGridSize + 1)
        val indexCount = clampedGridSize * clampedGridSize * 6 // 2 triangles per quad, 3 vertices each
        
        val vertices = FloatArray(vertexCount * VERTEX_STRIDE)
        val indices = ShortArray(indexCount)
        
        val halfWidth = width / 2.0f
        val halfHeight = height / 2.0f
        val stepX = width / clampedGridSize
        val stepY = height / clampedGridSize
        
        // Generate vertices
        var vertexIndex = 0
        for (row in 0..clampedGridSize) {
            for (col in 0..clampedGridSize) {
                val x = -halfWidth + col * stepX
                val y = -halfHeight + row * stepY
                val z = 0.0f
                
                // Position
                vertices[vertexIndex++] = x
                vertices[vertexIndex++] = y
                vertices[vertexIndex++] = z
                
                // Normal (pointing up for flat plane)
                vertices[vertexIndex++] = 0.0f
                vertices[vertexIndex++] = 0.0f
                vertices[vertexIndex++] = 1.0f
                
                // Texture coordinates
                val u = col.toFloat() / clampedGridSize
                val v = row.toFloat() / clampedGridSize
                vertices[vertexIndex++] = u
                vertices[vertexIndex++] = v
            }
        }
        
        // Generate indices (triangle strip)
        var indexIndex = 0
        for (row in 0 until clampedGridSize) {
            for (col in 0 until clampedGridSize) {
                val topLeft = row * (clampedGridSize + 1) + col
                val topRight = topLeft + 1
                val bottomLeft = (row + 1) * (clampedGridSize + 1) + col
                val bottomRight = bottomLeft + 1
                
                // First triangle
                indices[indexIndex++] = topLeft.toShort()
                indices[indexIndex++] = bottomLeft.toShort()
                indices[indexIndex++] = topRight.toShort()
                
                // Second triangle
                indices[indexIndex++] = topRight.toShort()
                indices[indexIndex++] = bottomLeft.toShort()
                indices[indexIndex++] = bottomRight.toShort()
            }
        }
        
        return MeshData(vertices, indices, vertexCount, indexCount, clampedGridSize)
    }
    
    /**
     * Apply curl deformation to mesh vertices
     * 
     * @param meshData Base mesh data
     * @param curlFactor Intensity of curl (0.0 = flat, 1.0 = full curl)
     * @param bendAxis Axis of bending (0 = X, 1 = Y)
     * @param curlPosition Position of curl along the axis (0.0 to 1.0)
     * @return Modified vertex array with curl applied
     */
    fun applyCurlDeformation(
        meshData: MeshData,
        curlFactor: Float,
        bendAxis: Int = 0,
        curlPosition: Float = 0.5f
    ): FloatArray {
        val vertices = meshData.vertices.clone()
        val gridSize = meshData.gridSize
        val curlRad = curlFactor * Math.PI.toFloat() // Convert to radians
        
        var vertexIndex = 0
        for (row in 0..gridSize) {
            for (col in 0..gridSize) {
                val baseIndex = vertexIndex
                
                val x = vertices[baseIndex]
                val y = vertices[baseIndex + 1]
                var z = vertices[baseIndex + 2]
                
                // Calculate curl based on position and axis
                val t = if (bendAxis == 0) {
                    // Bending along X axis
                    (x + 0.5f) * curlPosition
                } else {
                    // Bending along Y axis
                    (y + 0.5f) * curlPosition
                }
                
                // Apply sinusoidal curl deformation
                val curlOffset = sin(t * curlRad) * curlFactor * 0.2f
                z += curlOffset
                
                // Update position
                vertices[baseIndex + 2] = z
                
                // Recalculate normal based on curvature
                val normalZ = cos(t * curlRad)
                val normalX = if (bendAxis == 0) -sin(t * curlRad) * curlFactor else 0.0f
                val normalY = if (bendAxis == 1) -sin(t * curlRad) * curlFactor else 0.0f
                
                // Normalize normal vector
                val length = kotlin.math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
                if (length > 0.0001f) {
                    vertices[baseIndex + 3] = normalX / length
                    vertices[baseIndex + 4] = normalY / length
                    vertices[baseIndex + 5] = normalZ / length
                }
                
                vertexIndex += VERTEX_STRIDE
            }
        }
        
        return vertices
    }
    
    /**
     * Apply complex multi-point deformation for realistic paper behavior
     * Supports multiple bend points and elastic deformation
     * 
     * @param meshData Base mesh data
     * @param params Deformation parameters
     * @return Modified vertex array
     */
    fun applyComplexDeformation(
        meshData: MeshData,
        params: DeformationParams
    ): FloatArray {
        val vertices = meshData.vertices.clone()
        val gridSize = meshData.gridSize
        
        var vertexIndex = 0
        for (row in 0..gridSize) {
            for (col in 0..gridSize) {
                val baseIndex = vertexIndex
                
                val x = vertices[baseIndex]
                val y = vertices[baseIndex + 1]
                var z = vertices[baseIndex + 2]
                
                // Apply primary curl
                val primaryCurl = calculatePrimaryCurl(x, y, params)
                z += primaryCurl.z
                
                // Apply secondary wave for realism
                val waveEffect = calculateWaveEffect(x, y, params)
                z += waveEffect
                
                // Apply edge softness
                val edgeSoftness = calculateEdgeSoftness(x, y, params)
                z *= edgeSoftness
                
                // Update position
                vertices[baseIndex + 2] = z
                
                // Recalculate normal using finite differences
                val normal = calculateNormalAtPoint(col, row, gridSize, params)
                vertices[baseIndex + 3] = normal.x
                vertices[baseIndex + 4] = normal.y
                vertices[baseIndex + 5] = normal.z
                
                vertexIndex += VERTEX_STRIDE
            }
        }
        
        return vertices
    }
    
    private fun calculatePrimaryCurl(x: Float, y: Float, params: DeformationParams): Vector3 {
        val t = (x + 0.5f) * params.curlPosition
        val curlAmount = sin(t * params.curlFactor * Math.PI.toFloat()) * params.curlIntensity
        return Vector3(0f, 0f, curlAmount)
    }
    
    private fun calculateWaveEffect(x: Float, y: Float, params: DeformationParams): Float {
        if (!params.enableWaveEffect) return 0f
        
        val waveFreq = params.waveFrequency
        val waveAmp = params.waveAmplitude
        return sin(x * waveFreq) * cos(y * waveFreq) * waveAmp
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
    
    private fun calculateNormalAtPoint(
        col: Int,
        row: Int,
        gridSize: Int,
        params: DeformationParams
    ): Vector3 {
        // Simple normal calculation using neighboring points
        val dx = 1.0f / gridSize
        val dy = 1.0f / gridSize
        
        val zLeft = if (col > 0) getDeformedZ(col - 1, row, gridSize, params) else getDeformedZ(col, row, gridSize, params)
        val zRight = if (col < gridSize) getDeformedZ(col + 1, row, gridSize, params) else getDeformedZ(col, row, gridSize, params)
        val zBottom = if (row > 0) getDeformedZ(col, row - 1, gridSize, params) else getDeformedZ(col, row, gridSize, params)
        val zTop = if (row < gridSize) getDeformedZ(col, row + 1, gridSize, params) else getDeformedZ(col, row, gridSize, params)
        
        val tangentX = Vector3(dx, 0f, zRight - zLeft)
        val tangentY = Vector3(0f, dy, zTop - zBottom)
        
        // Cross product for normal
        val normal = crossProduct(tangentX, tangentY)
        return normalize(normal)
    }
    
    private fun getDeformedZ(col: Int, row: Int, gridSize: Int, params: DeformationParams): Float {
        val x = -0.5f + col.toFloat() / gridSize
        val y = -0.5f + row.toFloat() / gridSize
        return calculatePrimaryCurl(x, y, params).z + calculateWaveEffect(x, y, params)
    }
    
    private fun crossProduct(a: Vector3, b: Vector3): Vector3 {
        return Vector3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        )
    }
    
    private fun normalize(v: Vector3): Vector3 {
        val len = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        if (len < 0.0001f) return Vector3(0f, 0f, 1f)
        return Vector3(v.x / len, v.y / len, v.z / len)
    }
    
    /**
     * Get or create a pooled mesh for reuse
     */
    fun getPooledMesh(gridSize: Int = DEFAULT_GRID_SIZE): PooledMesh {
        val poolKey = gridSize
        
        // Check if we have an available mesh in the pool
        val pooled = meshPool.find { it.meshData.gridSize == gridSize && !activeMeshes.containsValue(it) }
        if (pooled != null) {
            activeMeshes[poolKey] = pooled
            return pooled
        }
        
        // Create new mesh
        val meshData = generatePlaneMesh(gridSize = gridSize)
        val pooledMesh = PooledMesh(meshData)
        meshPool.add(pooledMesh)
        activeMeshes[poolKey] = pooledMesh
        
        Log.d(TAG, "Created new pooled mesh for gridSize=$gridSize, total pooled: ${meshPool.size}")
        return pooledMesh
    }
    
    /**
     * Release a mesh back to the pool
     */
    fun releaseMesh(mesh: PooledMesh) {
        activeMeshes.entries.removeIf { it.value == mesh }
        mesh.isDirty = true
    }
    
    /**
     * Clear all pooled meshes to free memory
     */
    fun clearPool() {
        meshPool.clear()
        activeMeshes.clear()
    }
    
    /**
     * Parameters for complex deformation
     */
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
    
    private data class Vector3(val x: Float, val y: Float, val z: Float)
}
