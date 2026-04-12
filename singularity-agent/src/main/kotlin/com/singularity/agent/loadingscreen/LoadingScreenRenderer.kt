package com.singularity.agent.loadingscreen

import org.slf4j.LoggerFactory

/**
 * Loading screen renderer — GLFW window + OpenGL.
 *
 * Wzorowany na NeoForge FancyModLoader EarlyDisplay:
 * - Osobny render thread z GLFW window
 * - OpenGL 2.0 immediate mode (prostota > wydajność dla loading screen)
 * - Progress bar + stage text + tip + mod list
 * - Zamykany gdy LoadingScreenState.finished == true
 *
 * LWJGL jest na MC classpath (agent działa jako javaagent w MC process).
 * Na serwerze lub w testach (brak LWJGL): graceful fallback do console logging.
 *
 * Lifecycle:
 * 1. start() — tworzy render thread
 * 2. Render thread: glfwInit → createWindow → render loop (30fps)
 * 3. Bootstrap wpisuje progress do LoadingScreenState
 * 4. state.markFinished() → render loop zamyka window, thread exits
 * 5. stop() — wymusza zamknięcie jeśli jeszcze działa
 */
class LoadingScreenRenderer(
    private val state: LoadingScreenState,
    private val windowTitle: String = "SingularityMC — Loading...",
    private val windowWidth: Int = 854,
    private val windowHeight: Int = 480
) {
    private val logger = LoggerFactory.getLogger(LoadingScreenRenderer::class.java)

    @Volatile private var running = false
    private var renderThread: Thread? = null
    private var lwjglAvailable = false

    fun start() {
        if (running) return
        running = true

        // Sprawdź dostępność LWJGL
        lwjglAvailable = try {
            Class.forName("org.lwjgl.glfw.GLFW")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        // macOS requires GLFW calls on main thread — our render thread would segfault.
        // Detect and fall back to console on macOS.
        val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
        if (isMacOs && lwjglAvailable) {
            logger.info("macOS detected — GLFW requires main thread, using console fallback")
            lwjglAvailable = false
        }

        renderThread = Thread {
            if (lwjglAvailable) {
                runOpenGLLoop()
            } else {
                runConsoleFallback()
            }
        }.apply {
            name = "singularity-loading-screen"
            isDaemon = true
            start()
        }
    }

    private fun runOpenGLLoop() {
        try {
            // Dynamic import — LWJGL może nie być na classpath
            val glfwClass = Class.forName("org.lwjgl.glfw.GLFW")
            val gl11Class = Class.forName("org.lwjgl.opengl.GL11")
            val glClass = Class.forName("org.lwjgl.opengl.GL")

            // glfwInit()
            val glfwInit = glfwClass.getMethod("glfwInit")
            val initResult = glfwInit.invoke(null) as Boolean
            if (!initResult) {
                logger.warn("GLFW init failed — falling back to console")
                runConsoleFallback()
                return
            }

            // glfwDefaultWindowHints()
            glfwClass.getMethod("glfwDefaultWindowHints").invoke(null)

            // glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
            val glfwWindowHint = glfwClass.getMethod("glfwWindowHint", Int::class.java, Int::class.java)
            val GLFW_VISIBLE = glfwClass.getField("GLFW_VISIBLE").getInt(null)
            val GLFW_TRUE = glfwClass.getField("GLFW_TRUE").getInt(null)
            val GLFW_RESIZABLE = glfwClass.getField("GLFW_RESIZABLE").getInt(null)
            val GLFW_FALSE = glfwClass.getField("GLFW_FALSE").getInt(null)
            glfwWindowHint.invoke(null, GLFW_VISIBLE, GLFW_TRUE)
            glfwWindowHint.invoke(null, GLFW_RESIZABLE, GLFW_FALSE)

            // window = glfwCreateWindow(w, h, title, NULL, NULL)
            val glfwCreateWindow = glfwClass.getMethod("glfwCreateWindow",
                Int::class.java, Int::class.java, CharSequence::class.java, Long::class.java, Long::class.java)
            val window = glfwCreateWindow.invoke(null, windowWidth, windowHeight, windowTitle, 0L, 0L) as Long
            if (window == 0L) {
                logger.warn("GLFW window creation failed — falling back to console")
                runConsoleFallback()
                return
            }

            // glfwMakeContextCurrent(window)
            glfwClass.getMethod("glfwMakeContextCurrent", Long::class.java).invoke(null, window)

            // GL.createCapabilities()
            glClass.getMethod("createCapabilities").invoke(null)

            // glfwSwapInterval(1) — vsync
            glfwClass.getMethod("glfwSwapInterval", Int::class.java).invoke(null, 1)

            val glfwShouldClose = glfwClass.getMethod("glfwWindowShouldClose", Long::class.java)
            val glfwSwapBuffers = glfwClass.getMethod("glfwSwapBuffers", Long::class.java)
            val glfwPollEvents = glfwClass.getMethod("glfwPollEvents")
            val glfwDestroyWindow = glfwClass.getMethod("glfwDestroyWindow", Long::class.java)

            // GL11 methods
            val glClearColor = gl11Class.getMethod("glClearColor",
                Float::class.java, Float::class.java, Float::class.java, Float::class.java)
            val glClear = gl11Class.getMethod("glClear", Int::class.java)
            val GL_COLOR_BUFFER_BIT = gl11Class.getField("GL_COLOR_BUFFER_BIT").getInt(null)
            val glBegin = gl11Class.getMethod("glBegin", Int::class.java)
            val glEnd = gl11Class.getMethod("glEnd")
            val glVertex2f = gl11Class.getMethod("glVertex2f", Float::class.java, Float::class.java)
            val glColor3f = gl11Class.getMethod("glColor3f", Float::class.java, Float::class.java, Float::class.java)
            val GL_QUADS = gl11Class.getField("GL_QUADS").getInt(null)
            val glMatrixMode = gl11Class.getMethod("glMatrixMode", Int::class.java)
            val glLoadIdentity = gl11Class.getMethod("glLoadIdentity")
            val glOrtho = gl11Class.getMethod("glOrtho",
                Double::class.java, Double::class.java, Double::class.java,
                Double::class.java, Double::class.java, Double::class.java)
            val GL_PROJECTION = gl11Class.getField("GL_PROJECTION").getInt(null)
            val GL_MODELVIEW = gl11Class.getField("GL_MODELVIEW").getInt(null)

            // Set up 2D projection
            glMatrixMode.invoke(null, GL_PROJECTION)
            glLoadIdentity.invoke(null)
            glOrtho.invoke(null, 0.0, windowWidth.toDouble(), windowHeight.toDouble(), 0.0, -1.0, 1.0)
            glMatrixMode.invoke(null, GL_MODELVIEW)
            glLoadIdentity.invoke(null)

            logger.info("Loading screen window created ({}x{})", windowWidth, windowHeight)

            // Render loop
            while (running && !(glfwShouldClose.invoke(null, window) as Boolean)) {
                // Dark background (#1a1a2e)
                glClearColor.invoke(null, 0.102f, 0.102f, 0.180f, 1.0f)
                glClear.invoke(null, GL_COLOR_BUFFER_BIT)

                val progress = state.getProgress() / 100f

                // Progress bar background (dark gray)
                val barX = 100f
                val barY = windowHeight - 80f
                val barW = windowWidth - 200f
                val barH = 24f

                glColor3f.invoke(null, 0.2f, 0.2f, 0.25f)
                glBegin.invoke(null, GL_QUADS)
                glVertex2f.invoke(null, barX, barY)
                glVertex2f.invoke(null, barX + barW, barY)
                glVertex2f.invoke(null, barX + barW, barY + barH)
                glVertex2f.invoke(null, barX, barY + barH)
                glEnd.invoke(null)

                // Progress bar fill (purple gradient: #7c3aed)
                glColor3f.invoke(null, 0.486f, 0.227f, 0.929f)
                glBegin.invoke(null, GL_QUADS)
                glVertex2f.invoke(null, barX, barY)
                glVertex2f.invoke(null, barX + barW * progress, barY)
                glVertex2f.invoke(null, barX + barW * progress, barY + barH)
                glVertex2f.invoke(null, barX, barY + barH)
                glEnd.invoke(null)

                // Title area (brighter quad at top)
                glColor3f.invoke(null, 0.486f, 0.227f, 0.929f)
                glBegin.invoke(null, GL_QUADS)
                glVertex2f.invoke(null, 0f, 0f)
                glVertex2f.invoke(null, windowWidth.toFloat(), 0f)
                glVertex2f.invoke(null, windowWidth.toFloat(), 4f)
                glVertex2f.invoke(null, 0f, 4f)
                glEnd.invoke(null)

                glfwSwapBuffers.invoke(null, window)
                glfwPollEvents.invoke(null)

                if (state.finished) {
                    // Krótka pauza żeby user zdążył zobaczyć 100%
                    Thread.sleep(300)
                    break
                }

                // ~30 fps cap
                Thread.sleep(33)
            }

            // Cleanup window — GLFW stays initialized (MC reuses it)
            glfwDestroyWindow.invoke(null, window)
            logger.info("Loading screen closed")

        } catch (e: Exception) {
            logger.warn("OpenGL loading screen failed: {} — falling back to console", e.message)
            runConsoleFallback()
        }
    }

    private fun runConsoleFallback() {
        logger.info("Loading screen: console fallback mode")
        var lastPrintedProgress = -1
        while (running && !state.finished) {
            val progress = state.getProgress()
            if (progress != lastPrintedProgress) {
                val stage = state.getCurrentStage()
                val modCount = state.getLoadedModCount()
                val totalMods = state.getTotalModCount()
                logger.info("[LoadingScreen] {}% — {} (mods: {}/{})",
                    progress, stage, modCount, totalMods)
                lastPrintedProgress = progress
            }
            Thread.sleep(500)
        }
        logger.info("[LoadingScreen] Complete")
    }

    fun stop() {
        running = false
        renderThread?.join(2000)
    }
}
