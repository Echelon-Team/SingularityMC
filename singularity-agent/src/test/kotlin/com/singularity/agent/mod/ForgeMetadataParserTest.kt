package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class ForgeMetadataParserTest {

    private val dummyPath = Paths.get("/mods/test.jar")

    @Test
    fun `parses complete Create-like mods_toml`() {
        val toml = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="create"
            version="0.5.1.f"
            displayName="Create"
            authors="simibubi"
            description="Aesthetic Technology that empowers the Player"

            [[dependencies.create]]
            modId="forge"
            mandatory=true
            versionRange="[47.1.0,)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.create]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.20.2)"
            ordering="NONE"
            side="BOTH"

            [[mixins]]
            config="create.mixins.json"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("create", result.modId)
        assertEquals("0.5.1.f", result.version)
        assertEquals("Create", result.name)
        assertEquals(LoaderType.FORGE, result.loaderType)
        assertEquals("simibubi", result.authors.firstOrNull())
        assertEquals("Aesthetic Technology that empowers the Player", result.description)

        // Dependencies
        assertEquals(2, result.dependencies.size)
        val forgeDep = result.dependencies.first { it.modId == "forge" }
        assertTrue(forgeDep.required)
        assertEquals("[47.1.0,)", forgeDep.versionRange)

        // Mixins
        assertEquals(listOf("create.mixins.json"), result.mixinConfigs)
    }

    @Test
    fun `parses minimal mods_toml`() {
        val toml = """
            modLoader="javafml"
            loaderVersion="[47,)"

            [[mods]]
            modId="simple"
            version="1.0"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("simple", result.modId)
        assertEquals("1.0", result.version)
        assertEquals("simple", result.name) // brak displayName → fallback do modId
        assertEquals(ModSide.BOTH, result.side) // default
        assertTrue(result.dependencies.isEmpty())
        assertTrue(result.mixinConfigs.isEmpty())
    }

    @Test
    fun `handles mandatory false as optional dependency`() {
        val toml = """
            [[mods]]
            modId="mymod"
            version="1.0"

            [[dependencies.mymod]]
            modId="jei"
            mandatory=false
            versionRange="*"
            side="CLIENT"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals(1, result.dependencies.size)
        assertFalse(result.dependencies[0].required)
        assertEquals("jei", result.dependencies[0].modId)
    }

    @Test
    fun `handles side field in dependency without affecting mod side`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"

            [[dependencies.test]]
            modId="dep"
            mandatory=true
            versionRange="*"
            side="CLIENT"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        // ModSide na modzie samym (nie na dependency) — domyślnie BOTH
        assertEquals(ModSide.BOTH, result.side)
    }

    @Test
    fun `handles quoted and unquoted keys with spaces`() {
        val toml = """
            [[mods]]
            modId = "quoted"
            version = "1.0.0"
            displayName = "Quoted Mod"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("quoted", result.modId)
        assertEquals("Quoted Mod", result.name)
    }

    @Test
    fun `multiple mixin configs`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"

            [[mixins]]
            config="test.mixins.json"
            [[mixins]]
            config="test.client.mixins.json"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals(listOf("test.mixins.json", "test.client.mixins.json"), result.mixinConfigs)
    }

    @Test
    fun `missing modId throws exception`() {
        val toml = """
            [[mods]]
            version="1.0"
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ForgeMetadataParser.parse(toml, dummyPath)
        }
    }

    @Test
    fun `jarPath is preserved`() {
        val path = Paths.get("/instances/test/mods/forge-mod.jar")
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, path)
        assertEquals(path, result.jarPath)
    }

    @Test
    fun `triple-quoted multi-line description parses correctly`() {
        // Real-world Forge mods (np. Create, Twilight Forest) uzywaja triple-quoted description
        val toml = "[[mods]]\n" +
            "modId=\"bigmod\"\n" +
            "version=\"1.0\"\n" +
            "description='''A very long description\n" +
            "that spans multiple lines.\n" +
            "End of description.'''\n"

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("bigmod", result.modId)
        // Description powinno zachowac wszystkie linie (newlines included)
        assertTrue(result.description.contains("A very long description"))
        assertTrue(result.description.contains("multiple lines"))
        assertTrue(result.description.contains("End of description"))
    }

    @Test
    fun `comments are ignored`() {
        val toml = """
            # Top-level comment
            modLoader="javafml"

            [[mods]]
            # Mod comment
            modId="test"
            version="1.0"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("test", result.modId)
        assertEquals("1.0", result.version)
    }

    @Test
    fun `empty authors field produces empty authors list`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertTrue(result.authors.isEmpty())
    }

    @Test
    fun `multi-line description exact content preservation`() {
        // Test that newlines are ACTUALLY preserved — not just that fragments exist
        val toml = "[[mods]]\n" +
            "modId=\"bigmod\"\n" +
            "version=\"1.0\"\n" +
            "description='''Line one\n" +
            "Line two\n" +
            "Line three'''\n"

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        // Exact match: newlines preserved
        assertEquals("Line one\nLine two\nLine three", result.description)
    }

    @Test
    fun `comma-separated authors are split`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"
            authors="Alice, Bob, Carol"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals(listOf("Alice", "Bob", "Carol"), result.authors)
    }

    @Test
    fun `single author unchanged by comma split`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"
            authors="Alice"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals(listOf("Alice"), result.authors)
    }

    @Test
    fun `TOML array syntax authors are split`() {
        val toml = """
            [[mods]]
            modId="test"
            version="1.0"
            authors=["Alice","Bob"]
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals(listOf("Alice", "Bob"), result.authors)
    }

    @Test
    fun `multiple mods blocks — last wins with warning logged`() {
        // Real mody (Quark, Tetra) czasem mają wiele [[mods]] per JAR — plan zakłada single.
        // Fallback: bierzemy ostatni, logujemy warn. Test sprawdza że parser nie crashuje
        // i wybiera ostatni.
        val toml = """
            [[mods]]
            modId="first-mod"
            version="1.0"

            [[mods]]
            modId="last-mod"
            version="2.0"
        """.trimIndent()

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("last-mod", result.modId) // last wins
        assertEquals("2.0", result.version)
    }

    @Test
    fun `UTF-8 BOM handled in TOML`() {
        val toml = "\uFEFF[[mods]]\nmodId=\"bom-test\"\nversion=\"1.0\""

        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("bom-test", result.modId)
    }

    @Test
    fun `unclosed multi-line string does not crash`() {
        // Broken TOML: opening `'''` ale brak zamykającego
        val toml = "[[mods]]\n" +
            "modId=\"broken\"\n" +
            "version=\"1.0\"\n" +
            "description='''Never closed\n" +
            "Still going\n"

        // Parser powinien gracefully log warn i emitować best-effort
        val result = ForgeMetadataParser.parse(toml, dummyPath)
        assertEquals("broken", result.modId)
        // description może być partial ale parser nie crashuje
    }
}
