package com.singularity.launcher.service.runner

import com.singularity.common.model.InstanceConfig
import com.singularity.common.model.InstanceType
import com.singularity.common.model.LoaderType
import com.singularity.launcher.service.auth.MinecraftAccount
import com.singularity.launcher.service.auth.MinecraftProfile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class McRunnerTest {

    @Test
    fun `toUuidWithDashes converts 32 char to dashed`() {
        val input = "8667ba71b85a4004af54457a9734eed7"
        val expected = "8667ba71-b85a-4004-af54-457a9734eed7"
        assertEquals(expected, input.toUuidWithDashes())
    }

    @Test
    fun `toUuidWithDashes idempotent for already dashed`() {
        val dashed = "8667ba71-b85a-4004-af54-457a9734eed7"
        assertEquals(dashed, dashed.toUuidWithDashes())
    }

    @Test
    fun `buildCommand includes all required game args`() {
        val context = fakeContext(type = InstanceType.VANILLA)
        val command = McRunner.buildCommand(context, ramMb = 4096)

        assertTrue(command.contains("--username"))
        assertTrue(command.contains("Steve"))
        assertTrue(command.contains("--version"))
        assertTrue(command.contains("1.20.1"))
        assertTrue(command.contains("--gameDir"))
        assertTrue(command.contains("--assetsDir"))
        assertTrue(command.contains("--assetIndex"))
        assertTrue(command.contains("--uuid"))
        assertTrue(command.contains("--accessToken"))
        assertTrue(command.contains("--userType"))
    }

    @Test
    fun `buildCommand for offline sets userType legacy`() {
        val context = fakeContext()
        val command = McRunner.buildCommand(context, ramMb = 4096)

        val userTypeIdx = command.indexOf("--userType")
        assertTrue(userTypeIdx >= 0)
        assertEquals("legacy", command[userTypeIdx + 1])
    }

    @Test
    fun `buildCommand uses main class net_minecraft_client_main_Main`() {
        val context = fakeContext()
        val command = McRunner.buildCommand(context, ramMb = 4096)
        assertTrue(command.contains("net.minecraft.client.main.Main"))
    }

    @Test
    fun `buildCommand for Enhanced includes javaagent flag`() {
        val context = fakeContext(type = InstanceType.ENHANCED, agentPath = Path.of("/opt/singularity-agent.jar"))
        val command = McRunner.buildCommand(context, ramMb = 4096)

        assertTrue(command.any { it.startsWith("-javaagent:") })
        assertTrue(command.contains("-XX:+UseZGC"))
    }

    @Test
    fun `buildCommand for Vanilla excludes javaagent`() {
        val context = fakeContext(type = InstanceType.VANILLA, agentPath = null)
        val command = McRunner.buildCommand(context, ramMb = 4096)

        assertFalse(command.any { it.startsWith("-javaagent:") })
    }

    @Test
    fun `buildCommand includes Xmx flag from ramMb`() {
        val context = fakeContext()
        val command = McRunner.buildCommand(context, ramMb = 8192)
        assertTrue(command.contains("-Xmx8192m"))
    }

    @Test
    fun `buildCommand uses converted dashed UUID in args`() {
        val context = fakeContext()
        val command = McRunner.buildCommand(context, ramMb = 4096)

        val uuidIdx = command.indexOf("--uuid")
        val uuid = command[uuidIdx + 1]
        assertTrue(uuid.contains("-"), "UUID should be dashed format")
        assertEquals(36, uuid.length, "Dashed UUID is 36 chars")
    }

    private fun fakeContext(
        type: InstanceType = InstanceType.VANILLA,
        agentPath: Path? = null
    ) = LaunchContext(
        config = InstanceConfig(
            name = "Test",
            minecraftVersion = "1.20.1",
            type = type,
            loader = LoaderType.NONE,
            ramMb = 4096,
            threads = 4
        ),
        instanceDir = Path.of("/tmp/instance"),
        account = MinecraftAccount(
            id = "8667ba71b85a4004af54457a9734eed7",
            profile = MinecraftProfile(
                id = "8667ba71b85a4004af54457a9734eed7",
                name = "Steve"
            ),
            isPremium = false
        ),
        javaPath = Path.of("/opt/java/bin/java"),
        gameDir = Path.of("/tmp/instance/minecraft"),
        assetsDir = Path.of("/tmp/shared/assets"),
        assetIndex = "1.20",
        agentJarPath = agentPath,
        classpath = listOf(Path.of("/tmp/minecraft.jar"))
    )
}
