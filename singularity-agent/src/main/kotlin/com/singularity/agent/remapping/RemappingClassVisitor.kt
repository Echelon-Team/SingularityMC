package com.singularity.agent.remapping

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * ASM ClassVisitor pipeline do remappingu bytecode'u klas.
 *
 * Uzywa ASM ClassRemapper z custom Remapper ktory deleguje do RemappingEngine.
 * RemappingEngine szuka w tabelach mappingow z inheritance-aware fallback.
 *
 * UWAGA 1 — ClassWriter(0), NIE COMPUTE_FRAMES:
 * COMPUTE_FRAMES wymagaloby ladowania WSZYSTKICH referencowanych klas do classloadera,
 * co jest niemozliwe w momencie remappingu (klasy jeszcze nie sa zaladowane).
 * Frames przeliczane w pozniejszym kroku pipeline'u (Sub 2b) po wszystkich transformacjach.
 *
 * UWAGA 2 — ClassWriter(0), NIE ClassWriter(reader, 0):
 * 2-arg form kopiuje constant pool z readera dla wydajnosci, ale jest
 * INCOMPATIBLE z ClassRemapper — remapping zmienia constant pool entries.
 * 1-arg (0) form jest wymagany. (Potwierdzone przez bytecode-safety review.)
 *
 * UWAGA 3 — reader.accept(visitor, 0), NIE SKIP_FRAMES:
 * Frames SA propagowane przez visitor chain i ClassRemapper je correctly remapuje.
 * Mechanizm: ClassRemapper nadpisuje visitMethod() zwracajac MethodRemapper ktory
 * override'uje visitFrame(type, local[], stack[]). Kazdy element local/stack jest
 * sprawdzany — jesli String (internal class name), wywolywane jest remapper.mapType()
 * ktore deleguje do map(). Type references w frames sa remapowane razem z reszta.
 *
 * **JESLI ustawimy SKIP_FRAMES**: writer pisze klase BEZ StackMapTable attribute.
 * Java 7+ verifier (single verifier since Java 13) WYMAGA StackMapTable dla class
 * version >= 50 (Java 6). V17 class bez StackMapTable → VerifyError na pierwszej
 * metodzie z branch. **Testy unit nie zlapa tego** bo nie robia defineClass + invoke
 * przez ClassLoader — verifier jest lazy. Dlatego `flag = 0` jest jedynym poprawnym.
 * (Caught by bytecode-safety review — SKIP_FRAMES bylo moim bledem.)
 *
 * Referencja: implementation design sekcja 4.3, ASM ClassRemapper docs.
 */
object RemappingClassVisitor {

    /**
     * Remapuje bytecode klasy przez engine.
     *
     * @param classBytes oryginalny bytecode klasy
     * @param engine silnik remappingu z mapping tables i inheritance tree
     * @return zremapowany bytecode z remapowanymi frames (gotowy do JVM load)
     */
    fun remap(classBytes: ByteArray, engine: RemappingEngine): ByteArray {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(0) // Bez COMPUTE_FRAMES — ClassRemapper remapuje istniejace frames
        val remapper = EngineBackedRemapper(engine)
        val visitor = ClassRemapper(writer, remapper)
        reader.accept(visitor, 0) // flag=0: frames propagate przez visitor chain, ClassRemapper je remapuje
        return writer.toByteArray()
    }

    /**
     * ASM Remapper delegujacy do RemappingEngine.
     * Implementuje 3 kluczowe metody: map (klasy), mapMethodName, mapFieldName.
     *
     * Uwaga: ASM Remapper's `mapDesc`/`mapSignature` sa implementowane w base class
     * i wywoluja `map()` dla kazdego type reference — descriptor/signature remapping
     * jest obslugiwany automatycznie przez override'owanie samego `map()`.
     */
    // ASM 9.x: Remapper() no-arg constructor jest deprecated, wymaga explicit API version.
    // Opcodes.ASM9 = target ASM API version 9 (nasz ASM 9.9.1 to supportuje).
    private class EngineBackedRemapper(private val engine: RemappingEngine) : Remapper(Opcodes.ASM9) {

        override fun map(internalName: String): String =
            engine.resolveClass(internalName)

        override fun mapMethodName(owner: String, name: String, descriptor: String): String {
            // AD7 guard (Sub 2b): NIGDY nie remapuj <init> (constructors) ani <clinit>
            // (static initializers). Jesli mapping table ma wpis dla special method,
            // to jest bug w .tiny generator lub ktos manualnie zepsul mapping file.
            //
            // Zrenameowany constructor → ClassFormatError ("Method <init> in class ...
            // has illegal signature") bo JVM wymaga ze constructors sa named "<init>".
            // Zrenameowany clinit → klasa nigdy nie inicjalizowana, static fields zostaja
            // default, runtime crashe w nieprzewidywalnych miejscach.
            //
            // Guard na name.startsWith("<") łapie OBA special methods plus teoretyczne
            // przyszłe <special> methods w przyszłych wersjach classfile format.
            if (name.startsWith("<")) return name
            return engine.resolveMethod(owner, name, descriptor)
        }

        override fun mapFieldName(owner: String, name: String, descriptor: String): String =
            engine.resolveField(owner, name)
    }
}
