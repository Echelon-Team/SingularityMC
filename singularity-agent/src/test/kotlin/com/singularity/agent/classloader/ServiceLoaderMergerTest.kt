// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.classloader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ServiceLoaderMergerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createJar(name: String, entries: Map<String, String>): Path {
        val path = tempDir.resolve(name)
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
            for ((entryName, content) in entries) {
                jos.putNextEntry(JarEntry(entryName))
                jos.write(content.toByteArray())
                jos.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `addService combines providers from multiple JARs`() {
        val merger = ServiceLoaderMerger()
        merger.addService("org.slf4j.spi.SLF4JServiceProvider", "ch.qos.logback.classic.spi.LogbackServiceProvider")
        merger.addService("org.slf4j.spi.SLF4JServiceProvider", "org.apache.logging.slf4j.SLF4JServiceProvider")

        val providers = merger.getProviders("org.slf4j.spi.SLF4JServiceProvider")
        assertEquals(2, providers.size)
        assertTrue("ch.qos.logback.classic.spi.LogbackServiceProvider" in providers)
        assertTrue("org.apache.logging.slf4j.SLF4JServiceProvider" in providers)
    }

    @Test
    fun `duplicate providers are deduplicated`() {
        val merger = ServiceLoaderMerger()
        merger.addService("com.example.Service", "com.example.Impl")
        merger.addService("com.example.Service", "com.example.Impl")
        assertEquals(1, merger.getProviders("com.example.Service").size)
    }

    @Test
    fun `getProviders returns empty list for unknown service`() {
        val merger = ServiceLoaderMerger()
        assertTrue(merger.getProviders("unknown.Service").isEmpty())
    }

    @Test
    fun `scanJar extracts providers from META-INF services entries`() {
        val jarPath = createJar("lib.jar", mapOf(
            "META-INF/services/com.example.Iface" to "com.example.Impl1\ncom.example.Impl2\n",
            "META-INF/services/com.other.Iface" to "# comment\ncom.other.Impl\n"
        ))

        val merger = ServiceLoaderMerger()
        JarFile(jarPath.toFile()).use { jar ->
            merger.scanJar(jar)
        }

        assertEquals(2, merger.getProviders("com.example.Iface").size)
        assertEquals(1, merger.getProviders("com.other.Iface").size)
        assertFalse(merger.getProviders("com.other.Iface").contains("# comment"))
    }

    @Test
    fun `scanJar handles empty lines and CRLF line endings`() {
        val jarPath = createJar("crlf.jar", mapOf(
            "META-INF/services/com.x.Y" to "com.x.A\r\n\r\ncom.x.B\r\n"
        ))

        val merger = ServiceLoaderMerger()
        JarFile(jarPath.toFile()).use { jar ->
            merger.scanJar(jar)
        }

        val providers = merger.getProviders("com.x.Y")
        assertEquals(2, providers.size)
        assertTrue("com.x.A" in providers)
        assertTrue("com.x.B" in providers)
    }

    @Test
    fun `getAllServices returns all registered service interface names`() {
        val merger = ServiceLoaderMerger()
        merger.addService("ServiceA", "ImplA")
        merger.addService("ServiceB", "ImplB")

        val all = merger.getAllServices()
        assertEquals(2, all.size)
        assertTrue("ServiceA" in all)
        assertTrue("ServiceB" in all)
    }
}
