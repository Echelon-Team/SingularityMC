package com.singularity.launcher.service.runner

import com.singularity.common.model.InstanceType

/**
 * Constructs launch command + starts MC process przez ProcessBuilder.
 *
 * **Sub 4 scope:** client launch only. Server launch → Sub 5 Task 22 ServerRunner (copy of
 * McRunner z zmienioną main class na `net.minecraft.server.Main` + `nogui` arg).
 */
object McRunner {

    /**
     * Build pełnej komendy dla ProcessBuilder. Returns list of command args.
     *
     * **Args structure:**
     * 1. java path (first element)
     * 2. JVM args (-Xmx, -XX:+UseZGC, -javaagent jeśli Enhanced, extra JVM args)
     * 3. -cp classpath
     * 4. Main class (`net.minecraft.client.main.Main`)
     * 5. Game args (--username, --version, --gameDir, etc)
     */
    fun buildCommand(context: LaunchContext, ramMb: Int): List<String> {
        val cmd = mutableListOf<String>()

        // 1. Java executable
        cmd.add(context.javaPath.toString())

        // 2. JVM args
        cmd.add("-Xmx${ramMb}m")
        cmd.add("-Xms512m")

        // Enhanced: javaagent + ZGC
        if (context.config.type == InstanceType.ENHANCED && context.agentJarPath != null) {
            cmd.add("-javaagent:${context.agentJarPath}=${context.instanceDir}")
            cmd.add("-XX:+UseZGC")
            cmd.add("-XX:+UnlockExperimentalVMOptions")
        }

        // Extra JVM args (z LauncherSettings global lub InstanceConfig)
        cmd.addAll(context.extraJvmArgs)
        if (context.config.jvmArgs.isNotBlank()) {
            cmd.addAll(context.config.jvmArgs.split(" ").filter { it.isNotBlank() })
        }

        // 3. Classpath (per-OS separator)
        val separator = System.getProperty("path.separator") ?: ":"
        cmd.add("-cp")
        cmd.add(context.classpath.joinToString(separator))

        // 4. Main class
        cmd.add("net.minecraft.client.main.Main")

        // 5. Game args
        cmd.add("--username")
        cmd.add(context.account.profile.name)
        cmd.add("--version")
        cmd.add(context.config.minecraftVersion)
        cmd.add("--gameDir")
        cmd.add(context.gameDir.toString())
        cmd.add("--assetsDir")
        cmd.add(context.assetsDir.toString())
        cmd.add("--assetIndex")
        cmd.add(context.assetIndex)

        // UUID dashed format (Mojang 32-char → 8-4-4-4-12)
        cmd.add("--uuid")
        cmd.add(context.account.profile.id.toUuidWithDashes())

        // Access token (offline: stub z "0" — MC tolerates)
        cmd.add("--accessToken")
        cmd.add(context.account.mcToken ?: "0")

        // User type (offline: legacy, Microsoft: msa — post Sub 5 patch)
        cmd.add("--userType")
        cmd.add(if (context.account.isPremium) "msa" else "legacy")

        return cmd
    }

    /**
     * Launch MC process. Returns `Process` reference dla monitoring (stdout piping,
     * exit detection).
     */
    fun launch(context: LaunchContext, ramMb: Int): Process {
        val command = buildCommand(context, ramMb)
        val pb = ProcessBuilder(command)
            .directory(context.gameDir.toFile())
            .redirectErrorStream(true)
        return pb.start()
    }
}
