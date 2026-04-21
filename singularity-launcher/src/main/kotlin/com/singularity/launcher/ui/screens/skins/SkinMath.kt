// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D vector dla skin viewer (pure math — zero Compose deps).
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
}

data class Vec2(val x: Float, val y: Float)

data class Vertex(val pos: Vec3, val uv: Vec2)

data class UvRect(val x: Float, val y: Float, val width: Float, val height: Float)

data class Face(val vertices: List<Vertex>, val uvRect: UvRect)

/**
 * Pure 3D math helpers — rotations, projection, z-sort. Testowalne bez Compose.
 */
object SkinMath {

    fun rotateY(point: Vec3, yaw: Float): Vec3 {
        val c = cos(yaw)
        val s = sin(yaw)
        return Vec3(
            x = point.x * c + point.z * s,
            y = point.y,
            z = -point.x * s + point.z * c
        )
    }

    fun rotateX(point: Vec3, pitch: Float): Vec3 {
        val c = cos(pitch)
        val s = sin(pitch)
        return Vec3(
            x = point.x,
            y = point.y * c - point.z * s,
            z = point.y * s + point.z * c
        )
    }

    fun rotateYX(point: Vec3, yaw: Float, pitch: Float): Vec3 {
        val y = rotateY(point, yaw)
        return rotateX(y, pitch)
    }

    /**
     * Perspective projection world space → screen space.
     */
    fun project(
        point: Vec3,
        canvasWidth: Float,
        canvasHeight: Float,
        cameraZ: Float = 50f,
        focalLength: Float = 500f
    ): Vec2 {
        val distance = cameraZ - point.z
        val scale = focalLength / distance
        val screenX = canvasWidth / 2f + point.x * scale
        val screenY = canvasHeight / 2f - point.y * scale
        return Vec2(screenX, screenY)
    }

    fun faceDepth(face: Face): Float =
        face.vertices.map { it.pos.z }.average().toFloat()

    fun zSortFaces(faces: List<Face>): List<Face> =
        faces.sortedBy { faceDepth(it) }

    fun walkSwing(phase: Double, amplitude: Double): Double =
        sin(phase) * amplitude

    fun clampYaw(yaw: Float): Float = yaw.coerceIn(-PI.toFloat(), PI.toFloat())

    fun clampPitch(pitch: Float): Float {
        val limit = (PI / 4).toFloat()
        return pitch.coerceIn(-limit, limit)
    }
}
