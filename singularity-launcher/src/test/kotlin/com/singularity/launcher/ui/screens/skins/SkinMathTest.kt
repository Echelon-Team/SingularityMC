// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.PI

class SkinMathTest {

    @Test
    fun `rotateY rotates point by yaw 90deg`() {
        val point = Vec3(1f, 0f, 0f)
        val rotated = SkinMath.rotateY(point, yaw = (PI / 2).toFloat())
        // 90° rotation around Y: (1,0,0) → (0,0,-1)
        assertEquals(0f, rotated.x, 0.001f)
        assertEquals(0f, rotated.y, 0.001f)
        assertEquals(-1f, rotated.z, 0.001f)
    }

    @Test
    fun `rotateX rotates point by pitch 90deg`() {
        val point = Vec3(0f, 1f, 0f)
        val rotated = SkinMath.rotateX(point, pitch = (PI / 2).toFloat())
        // 90° rotation around X: (0,1,0) → (0,0,1)
        assertEquals(0f, rotated.x, 0.001f)
        assertEquals(0f, rotated.y, 0.001f)
        assertEquals(1f, rotated.z, 0.001f)
    }

    @Test
    fun `rotateYX compose correctly`() {
        val point = Vec3(1f, 0f, 0f)
        val result = SkinMath.rotateYX(point, yaw = (PI / 2).toFloat(), pitch = 0f)
        // rotateY first: (1,0,0) → (0,0,-1); rotateX pitch=0 no-op
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
        assertEquals(-1f, result.z, 0.001f)
    }

    @Test
    fun `project converts 3D to 2D with perspective`() {
        val point = Vec3(10f, 0f, 0f)
        val screen = SkinMath.project(point, canvasWidth = 400f, canvasHeight = 400f, cameraZ = 50f, focalLength = 500f)
        // x=10/50 * 500 = 100, center screen 200 → 300
        assertEquals(300f, screen.x, 0.5f)
        assertEquals(200f, screen.y, 0.5f)
    }

    @Test
    fun `project places center for origin`() {
        val screen = SkinMath.project(Vec3(0f, 0f, 0f), 400f, 400f, 50f, 500f)
        assertEquals(200f, screen.x, 0.001f)
        assertEquals(200f, screen.y, 0.001f)
    }

    @Test
    fun `faceDepth average z of vertices`() {
        val face = Face(
            vertices = listOf(
                Vertex(Vec3(0f, 0f, 10f), Vec2(0f, 0f)),
                Vertex(Vec3(0f, 0f, 20f), Vec2(0f, 0f)),
                Vertex(Vec3(0f, 0f, 30f), Vec2(0f, 0f)),
                Vertex(Vec3(0f, 0f, 40f), Vec2(0f, 0f))
            ),
            uvRect = UvRect(0f, 0f, 8f, 8f)
        )
        assertEquals(25f, SkinMath.faceDepth(face), 0.001f)
    }

    @Test
    fun `zSortFaces orders back to front painter algorithm`() {
        val near = Face(listOf(Vertex(Vec3(0f, 0f, 10f), Vec2(0f, 0f))), UvRect(0f, 0f, 8f, 8f))
        val middle = Face(listOf(Vertex(Vec3(0f, 0f, 0f), Vec2(0f, 0f))), UvRect(0f, 0f, 8f, 8f))
        val far = Face(listOf(Vertex(Vec3(0f, 0f, -10f), Vec2(0f, 0f))), UvRect(0f, 0f, 8f, 8f))
        val sorted = SkinMath.zSortFaces(listOf(near, middle, far))
        assertEquals(far, sorted[0])
        assertEquals(middle, sorted[1])
        assertEquals(near, sorted[2])
    }

    @Test
    fun `walkSwing returns sin value for phase`() {
        assertEquals(0.0, SkinMath.walkSwing(0.0, amplitude = 1.0), 0.001)
        assertEquals(1.0, SkinMath.walkSwing(PI / 2, amplitude = 1.0), 0.001)
        assertEquals(0.0, SkinMath.walkSwing(PI, amplitude = 1.0), 0.001)
    }

    @Test
    fun `clampYaw keeps within -PI to PI`() {
        assertEquals(PI.toFloat(), SkinMath.clampYaw((PI + 1.0).toFloat()), 0.001f)
        assertEquals((-PI).toFloat(), SkinMath.clampYaw((-PI - 1.0).toFloat()), 0.001f)
        assertEquals(0.5f, SkinMath.clampYaw(0.5f), 0.001f)
    }

    @Test
    fun `clampPitch keeps within -PI over 4 to PI over 4`() {
        val limit = (PI / 4).toFloat()
        assertEquals(limit, SkinMath.clampPitch(1.0f), 0.001f)
        assertEquals(-limit, SkinMath.clampPitch(-1.0f), 0.001f)
        assertEquals(0.3f, SkinMath.clampPitch(0.3f), 0.001f)
    }
}
