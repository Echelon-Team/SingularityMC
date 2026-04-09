package com.singularity.agent.mixin

/**
 * Abstrakcja dla dostarczenia remapowanego bytecode'u Mixin framework'owi.
 *
 * Dlaczego: `SingularityMixinService.getClassNode(name)` musi zwrocic ClassNode z
 * MOJMAP method/field names, bo annotacje mixinow uzywaja mojmap (`@Inject(method = "tick")`)
 * a nie SRG (`m_5803_`). Jesli zwrocimy raw obf/SRG bytes, Mixin szukania method'u "tick"
 * zwroci nic → @Inject nigdy nie aplikowany → cala integracja Mixin dead.
 *
 * Implementacja (EngineMixinBytecodeSource) uzywa JarRegistry do znalezienia raw bytes
 * i RemappingClassVisitor.remap() do conversion na mojmap (BEZ full pipeline — to jest
 * kroki 2-3, nie uruchamiamy cache/mixin step zeby uniknac rekursji).
 *
 * Injection point: AgentMain po ServiceLoader discovery SingularityMixinService ustawia
 * `service.bytecodeSource = EngineMixinBytecodeSource(jarRegistry, engine)`.
 *
 * Referencja: AD6 w plan v2.3, design-compliance raport sekcja A.3 + C.2.
 */
interface MixinBytecodeSource {
    /**
     * Zwraca raw mojmap-remapped bytes dla klasy. Klasa MUSI byc zarejestrowana w
     * JarRegistry (wczesniej dodana przez addJar). Zwraca null jesli klasa nie istnieje.
     *
     * @param internalName mojmap internal name (np. "net/minecraft/world/entity/Entity")
     * @return remapped bytes lub null
     */
    fun getClassBytes(internalName: String): ByteArray?
}
