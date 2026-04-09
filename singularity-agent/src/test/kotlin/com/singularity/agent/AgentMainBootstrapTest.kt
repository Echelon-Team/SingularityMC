package com.singularity.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentMainBootstrapTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Fake Instrumentation dla testow bez real JVM agent.
     */
    private fun fakeInstrumentation(): java.lang.instrument.Instrumentation =
        object : java.lang.instrument.Instrumentation {
            override fun addTransformer(t: java.lang.instrument.ClassFileTransformer?, canRetransform: Boolean) {}
            override fun addTransformer(t: java.lang.instrument.ClassFileTransformer?) {}
            override fun removeTransformer(t: java.lang.instrument.ClassFileTransformer?): Boolean = false
            override fun isRetransformClassesSupported(): Boolean = true
            override fun retransformClasses(vararg classes: Class<*>) {}
            override fun isRedefineClassesSupported(): Boolean = true
            override fun redefineClasses(vararg definitions: java.lang.instrument.ClassDefinition) {}
            override fun isModifiableClass(theClass: Class<*>): Boolean = true
            override fun getAllLoadedClasses(): Array<Class<*>> = emptyArray()
            override fun getInitiatedClasses(loader: ClassLoader?): Array<Class<*>> = emptyArray()
            override fun getObjectSize(objectToSize: Any): Long = 0
            override fun appendToBootstrapClassLoaderSearch(jarfile: java.util.jar.JarFile?) {}
            override fun appendToSystemClassLoaderSearch(jarfile: java.util.jar.JarFile?) {}
            override fun isNativeMethodPrefixSupported(): Boolean = false
            override fun setNativeMethodPrefix(t: java.lang.instrument.ClassFileTransformer?, prefix: String?) {}
            override fun redefineModule(
                module: Module, extraReads: Set<Module>, extraExports: Map<String, Set<Module>>,
                extraOpens: Map<String, Set<Module>>, extraUses: Set<Class<*>>,
                extraProvides: Map<Class<*>, List<Class<*>>>
            ) {}
            override fun isModifiableModule(module: Module): Boolean = true
        }

    @Test
    fun `premain with null args runs in standalone mode without error`() {
        // Pass-through test: null args → early return, nie crashuje
        assertDoesNotThrow {
            AgentMain.premain(null, fakeInstrumentation())
        }
    }

    @Test
    fun `premain with non-existent instance dir throws RuntimeException`() {
        val fakeInst = fakeInstrumentation()
        val nonExistent = tempDir.resolve("missing-instance").toString()
        assertThrows(RuntimeException::class.java) {
            AgentMain.premain(nonExistent, fakeInst)
        }
    }
}
