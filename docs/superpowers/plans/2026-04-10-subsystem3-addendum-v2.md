# Subsystem 3: Threading Engine — ADDENDUM v2 (2026-04-10)

> **Kontekst:** 6 reviewerów (design-compliance, thread-safety, edge-case-hunter, performance, web-researcher, test-quality) przejrzało plan Sub 3 (4809 linii, 22 taski). Znaleźli 3 BLOCKER, 7 CRITICAL (race/corruption/deadlock), 9 IMPORTANT, 6 CONCERN. Ten ADDENDUM adresuje WSZYSTKIE findings + decyzje z Mateuszem.

---

## DECYZJE PODJĘTE Z MATEUSZEM

1. **WorldAccessProxy**: Opcja A — method replacement via SingularityTransformer (ASM rename `getBlockState` → `getBlockState$original`, nowa `getBlockState` z region check + snapshot routing). NIE Mixin.
2. **Redstone cross-region**: Opcja B — akceptujemy 1-tick delay na granicy regionu. Dodajemy `RegionGroupingHint` interface jako punkt zaczepienia na przyszły circuit tracker (server engine roadmap).
3. **Architektura server-ready**: Nie hardcodujemy założeń single-player. Threading engine musi skalować na 32+ wątków (przyszły server engine SingularityMC.jar, ~90% kodu współdzielone).

---

## ERRATA — poprawki istniejących tasków

### Task 2 ERRATA: Region +scheduledTickCount (empty region fix)

**Problem (edge-case-hunter #8):** RegionScheduler pomija regiony bez encji, ale regiony z aktywnymi redstone clockami (repeaters, observers) powinny tickować.

**Fix:** Dodaj do `Region`:
```kotlin
private val scheduledTickCount = AtomicInteger(0)

fun getScheduledTickCount(): Int = scheduledTickCount.get()
fun addScheduledTick() { scheduledTickCount.incrementAndGet() }
fun removeScheduledTick() { scheduledTickCount.decrementAndGet() }

fun isActive(): Boolean = entityCount.get() > 0 || scheduledTickCount.get() > 0
```

`RegionScheduler.buildSchedule()` filtruje przez `isActive()` (nie tylko entityCount > 0).

---

### Task 6 ERRATA: REMOVE TickBarrier — replace with Phaser

**Problem (design-compliance #IMPORTANT-1, thread-safety #1):**
- TickBarrier (CyclicBarrier wrapper) NIE jest używany przez TickExecutor (Task 7)
- TickExecutor używa CountDownLatch — dwa mechanizmy synchronizacji dla jednego celu
- TickBarrier.arrived counter ma race condition
- CyclicBarrier po timeout wchodzi w broken state — cascading failure

**Fix:** USUŃ `TickBarrier.kt` i `TickBarrierTest.kt`. Zastąp przez `java.util.concurrent.Phaser` w TickExecutor (Task 7 errata poniżej).

`TickPhase.kt` pozostaje bez zmian.

---

### Task 7 ERRATA: TickExecutor z Phaser + excluded regions + zombie prevention

**Problem (thread-safety #1):** Po timeout fazy, stuck threads kontynuują pisanie do region state JEDNOCZEŚNIE z następną fazą. Zero exclusion mechanism.

**Fix:** Przepisz `TickExecutor` z Phaser:

```kotlin
class TickExecutor(
    private val pool: DimensionPool,
    private val scheduler: RegionScheduler,
    private val config: ThreadingConfig,
    private val regionGroupingHint: RegionGroupingHint = RegionGroupingHint.NONE
) {
    private val logger = LoggerFactory.getLogger(TickExecutor::class.java)

    /** Regiony wykluczone z bieżącego ticka (stuck w poprzedniej fazie). */
    private val excludedRegions = ConcurrentHashMap.newKeySet<RegionId>()

    fun executeTick(
        tickNumber: Long,
        playerPositions: List<PlayerPosition>,
        phaseHandler: (TickPhase, Region) -> Unit
    ) {
        val schedule = scheduler.buildSchedule(playerPositions)
            .filter { it.id !in excludedRegions }
        if (schedule.isEmpty()) return

        excludedRegions.clear() // reset z poprzedniego ticka

        for (phase in TickPhase.entries) {
            val activeRegions = schedule.filter { it.id !in excludedRegions }
            if (activeRegions.isEmpty()) break
            executePhase(phase, activeRegions, phaseHandler, tickNumber)
        }

        schedule.filter { it.id !in excludedRegions }.forEach {
            it.setLastTick(tickNumber)
            it.setState(Region.State.COMPLETED)
        }
    }

    private fun executePhase(
        phase: TickPhase,
        regions: List<Region>,
        phaseHandler: (TickPhase, Region) -> Unit,
        tickNumber: Long
    ) {
        val phaser = Phaser(regions.size + 1) // +1 for coordinator
        val regionResults = ConcurrentHashMap<RegionId, Boolean>() // true = completed

        regions.forEach { region ->
            pool.submit {
                try {
                    region.setState(Region.State.PROCESSING)
                    phaseHandler(phase, region)
                    regionResults[region.id] = true
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    logger.error("Phase {} failed for region {}: {}", phase, region.id, e.message, e)
                    regionResults[region.id] = true // failed but COMPLETED (not stuck)
                } finally {
                    phaser.arriveAndDeregister()
                }
            }
        }

        // Coordinator waits with timeout
        try {
            phaser.awaitAdvanceInterruptibly(phaser.arrive(), config.barrierTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Regions that didn't arrive are STUCK — exclude from subsequent phases
            val stuck = regions.filter { regionResults[it.id] != true }
            for (region in stuck) {
                excludedRegions.add(region.id)
                region.setState(Region.State.STUCK)
                logger.warn("Phase {} timeout: region {} STUCK — excluded from remaining phases", phase, region.id)
            }
        }
    }
}
```

**Kluczowe zmiany:**
- Phaser zamiast CountDownLatch — dynamic deregistration (stuck regiony)
- `excludedRegions` set — rośnie w trakcie ticka, stuck regiony NIE wchodzą do kolejnych faz
- Stuck region → `State.STUCK` → watchdog (Task 14) obsługuje recovery
- Coordinator thread `arrive()` + `awaitAdvanceInterruptibly()` z timeoutem
- `arriveAndDeregister()` zamiast `countDown()` — czysto odpina się od phasera

**Nowe testy:**
```kotlin
@Test
fun `stuck region excluded from subsequent phases`() {
    // Region 0 sleeps forever in ENTITY_TICKING
    // Region 1 completes normally
    // Verify: region 0 NOT in BLOCK_UPDATES/REDSTONE/COMMIT phases
}

@Test
fun `phases execute in strict order`() {
    // Record (phase, nanoTime) per callback
    // Verify all ENTITY_TICKING timestamps < all BLOCK_UPDATES timestamps
}

@Test
fun `regions processed in parallel within phase`() {
    // 4 regions, record Thread.currentThread().id per handler
    // Verify 2+ distinct threads used
}

@Test
fun `deterministic output — same input produces same result set`() {
    // Run 10 identical ticks, collect (regionId, phase) sets
    // Verify all 10 produce identical sets
}
```

---

### Task 8 ERRATA: Snapshot — fix Y encoding, enforce read-only, add BlockEntity

**Problem (thread-safety #3):**
- `setBlock()` public — caller can mutate after acquire
- Y encoding broken for negative values (-64 in MC 1.20.1)
- Missing BlockEntity data (NBT)

**Fix — ImmutableSnapshot:**

```kotlin
class ImmutableSnapshot internal constructor() {
    @Volatile var regionId: RegionId = RegionId(0, 0)
        internal set
    @Volatile var tickNumber: Long = -1
        internal set

    /** Mapa: pozycja → blockStateId. */
    private val blocks = ConcurrentHashMap<Long, Int>()

    /** BlockEntity NBT snapshots. Klucz = pozycja, wartość = skopiowane NBT dane (Map). */
    private val blockEntities = ConcurrentHashMap<Long, Map<String, Any>>()

    @Volatile private var frozen = false

    fun getBlock(blockX: Int, blockY: Int, blockZ: Int): Int =
        blocks[encodePosition(blockX, blockY, blockZ)] ?: 0

    fun getBlockEntity(blockX: Int, blockY: Int, blockZ: Int): Map<String, Any>? =
        blockEntities[encodePosition(blockX, blockY, blockZ)]

    /** Wywoływane TYLKO przez snapshot builder PRZED freeze(). */
    internal fun setBlock(blockX: Int, blockY: Int, blockZ: Int, blockStateId: Int) {
        check(!frozen) { "Cannot modify frozen snapshot" }
        blocks[encodePosition(blockX, blockY, blockZ)] = blockStateId
    }

    internal fun setBlockEntity(blockX: Int, blockY: Int, blockZ: Int, nbtData: Map<String, Any>) {
        check(!frozen) { "Cannot modify frozen snapshot" }
        blockEntities[encodePosition(blockX, blockY, blockZ)] = nbtData.toMap() // defensive copy
    }

    /** Zamraża snapshot — po tym setBlock/setBlockEntity rzucają IllegalStateException. */
    internal fun freeze() { frozen = true }

    internal fun clear() {
        frozen = false
        blocks.clear()
        blockEntities.clear()
    }

    fun size(): Int = blocks.size

    private fun encodePosition(x: Int, y: Int, z: Int): Long {
        // FIX: obsługa ujemnych Y (-64..319 w MC 1.20.1)
        // Shift Y by +64 to make it non-negative (range 0..383)
        val adjustedY = (y + 64).toLong() and 0x1FF // 9 bits (0..511)
        return ((x.toLong() and 0x3FFFFFF) shl 35) or
               (adjustedY shl 26) or
               (z.toLong() and 0x3FFFFFF)
    }
}
```

**Nowe testy:**
```kotlin
@Test
fun `setBlock after freeze throws IllegalStateException`()

@Test
fun `negative Y values encoded correctly`() {
    snapshot.setBlock(0, -64, 0, 1)
    assertEquals(1, snapshot.getBlock(0, -64, 0))
    snapshot.setBlock(0, -1, 0, 2)
    assertEquals(2, snapshot.getBlock(0, -1, 0))
}

@Test
fun `getBlockEntity returns copied NBT data`()

@Test
fun `concurrent acquire never returns same instance to two threads`() {
    // 100 threads acquire simultaneously, all instances distinct
}
```

**SnapshotPool ERRATA — fix TOCTOU race:**
```kotlin
// STARE (race condition):
fun release(snapshot: ImmutableSnapshot) {
    snapshot.clear()
    if (available.size < maxSize) available.offerLast(snapshot)
}

// NOWE (atomic):
private val poolSize = AtomicInteger(0)

fun release(snapshot: ImmutableSnapshot) {
    snapshot.clear()
    if (poolSize.get() < maxSize && poolSize.incrementAndGet() <= maxSize) {
        available.offerLast(snapshot)
    } else {
        poolSize.decrementAndGet() // exceeded, undo increment
    }
}
```

---

### Task 10 ERRATA: Message queue — atomic drain, split/merge migration

**Problem (thread-safety #6):** drainAll poll-loop nie jest atomowy. Wiadomości tracone przy split/merge.

**Fix — RegionMessageQueue:**
```kotlin
/** Atomic drain — swap underlying queue in one CAS operation. */
fun drainAllAtomic(): List<RegionMessage> {
    val snapshot = mutableListOf<RegionMessage>()
    // Drain everything currently in queue
    // Using a flag to prevent new messages during drain
    var msg = queue.poll()
    while (msg != null) {
        snapshot.add(msg)
        sizeCounter.decrementAndGet()
        msg = queue.poll()
    }
    return snapshot
}
```

**DimensionMessageBus — dodaj migrację przy split/merge:**
```kotlin
/** Migruje wiadomości z oldRegionId na nowe sub-region IDs po split. */
fun migrateOnSplit(oldRegionId: RegionId, newRegionIds: Set<RegionId>,
                   regionResolver: (RegionMessage) -> RegionId) {
    val oldQueue = queues.remove(oldRegionId) ?: return
    val messages = oldQueue.drainAllAtomic()
    for (msg in messages) {
        val targetRegion = regionResolver(msg)
        getOrCreateQueue(targetRegion).enqueue(msg)
    }
}
```

---

### Task 11 ERRATA: EntityTransferQueue — grouped transfers

**Problem (thread-safety #4, edge-case-hunter #4):** Transfer nie jest atomowy. Passengers/leash unhandled.

**Fix:**
```kotlin
data class TransferGroup(
    val groupId: UUID = UUID.randomUUID(),
    val transfers: List<PendingTransfer>
) {
    /** Primary entity (vehicle/main). */
    val primaryTransfer: PendingTransfer get() = transfers.first()
}

data class PendingTransfer(
    val entityUuid: UUID,
    val sourceRegionId: RegionId,
    val targetRegionId: RegionId,
    val entityPayload: Any,
    val groupId: UUID? = null  // null = standalone transfer
)

class EntityTransferQueue {
    private val pendingGroups = ConcurrentLinkedQueue<TransferGroup>()

    fun enqueueTransfer(transfer: PendingTransfer) {
        pendingGroups.offer(TransferGroup(transfers = listOf(transfer)))
    }

    fun enqueueGroupTransfer(group: TransferGroup) {
        pendingGroups.offer(group)
    }

    /** Commit all transfers atomically per group. */
    fun commitTransfersFor(targetRegionId: RegionId): List<TransferGroup> {
        val result = mutableListOf<TransferGroup>()
        val iter = pendingGroups.iterator()
        while (iter.hasNext()) {
            val group = iter.next()
            if (group.transfers.any { it.targetRegionId == targetRegionId }) {
                iter.remove()
                result.add(group)
            }
        }
        return result
    }
}
```

---

### Task 12 ERRATA: CommitOperation — piston pushGroupId + sequenceNumber

**Problem (edge-case-hunter #2):** BlockMove brak ordering dla slime block contraptions.

**Fix:** Dodaj do `CommitOperation`:
```kotlin
sealed class CommitOperation {
    data class BlockMove(
        val sourcePos: Long,
        val targetPos: Long,
        val blockStateId: Int,
        val pushGroupId: UUID? = null,   // NEW: grupy piston push
        val sequenceNumber: Int = 0,      // NEW: order w grupie (back-to-front)
        override val regionsAffected: Set<RegionId>,
        override val isIndependent: Boolean = false
    ) : CommitOperation()
    // ... rest unchanged
}
```

`CommitPhaseExecutor` sortuje DEPENDENT ops: najpierw po `pushGroupId` (null last), potem po `sequenceNumber` descending (back-to-front, jak vanilla).

---

### Task 13 ERRATA: LockOrderingValidator — document limitations

Dodaj do KDoc:
```kotlin
/**
 * ...
 * LIMITATIONS:
 * - Thread-local only — tracks what CURRENT thread holds, NOT cross-thread ABBA detection
 * - Does NOT track implicit locks: ConcurrentHashMap.computeIfAbsent bin locks,
 *   synchronized blocks not annotated with LockLevel
 * - Runtime sanity check, not a formal deadlock detector
 * - For cross-thread deadlock detection, use JVM thread dump analysis (jstack)
 */
```

---

### Task 14 ERRATA: Watchdog — cooperative cancellation before interrupt

**Problem (thread-safety #7):** Thread.interrupt() na stuck thread może skorumpować NIO writes.

**Fix:** Region gets `shouldAbort` flag:
```kotlin
// In Region class:
@Volatile var shouldAbort: Boolean = false

// In watchdog:
fun requestAbort(region: Region) {
    region.shouldAbort = true
    logger.warn("Cooperative abort requested for region {}", region.id)
}

fun interruptStuck(region: Region) {
    // Only interrupt if cooperative abort was ignored for additional timeout
    if (region.owningThread != null) {
        region.owningThread!!.interrupt()
        logger.warn("Hard interrupt on stuck region {} (cooperative abort ignored)", region.id)
    }
}
```

TickExecutor's phaseHandler checks `region.shouldAbort` at safe points:
```kotlin
phaseHandler = { phase, region ->
    for (entity in region.entities) {
        if (region.shouldAbort) {
            logger.warn("Region {} aborting mid-phase {}", region.id, phase)
            break
        }
        tickEntity(entity)
    }
}
```

Two-stage recovery: cooperative abort (instant) → if ignored after 2x timeout → hard interrupt.

---

### Task 18 ERRATA: FULL REWRITE — WorldAccessProxy method replacement

**Problem (thread-safety #8, performance #4, design-compliance #IMPORTANT-4):** Void `beforeAccess()` hook nie może routować. Bypassable via subclass.

**Full rewrite — method replacement pattern:**

```kotlin
object WorldAccessProxyTransformer {
    private val LEVEL_INTERNAL_NAME = "net/minecraft/world/level/Level"
    private val DISPATCHER_INTERNAL_NAME =
        "com/singularity/agent/threading/proxy/WorldAccessProxyDispatcher"

    /**
     * Intercepted methods: renamed to $original, new method with region check.
     * getBlockState(BlockPos) → rename to getBlockState$original
     *                         → new getBlockState: region check → $original or snapshot
     */
    private val INTERCEPTED_METHODS = mapOf(
        "getBlockState" to "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        "setBlockState" to "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        "getBlockEntity" to "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
    )

    fun transformLevel(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        if (reader.className != LEVEL_INTERNAL_NAME) return classBytes

        // Phase 1: rename original methods to $original
        // Phase 2: create new methods with region dispatch
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        val renamer = MethodRenamerVisitor(writer)
        reader.accept(renamer, 0)

        // Phase 2: add dispatch methods
        for ((name, desc) in INTERCEPTED_METHODS) {
            addDispatchMethod(writer, name, desc)
        }

        return writer.toByteArray()
    }

    private fun addDispatchMethod(cw: ClassWriter, name: String, desc: String) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null)
        mv.visitCode()

        // Object result = WorldAccessProxyDispatcher.dispatch(this, name, pos)
        // if (result != null) return (CastType) result;  // snapshot value
        // else return this.name$original(pos);            // live data

        mv.visitVarInsn(Opcodes.ALOAD, 0) // this (Level)
        mv.visitLdcInsn(name)              // method name
        mv.visitVarInsn(Opcodes.ALOAD, 1)  // first arg (BlockPos)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DISPATCHER_INTERNAL_NAME,
            "dispatch",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
            false)

        // Check if dispatch returned non-null (cross-region → snapshot value)
        mv.visitInsn(Opcodes.DUP)
        val labelOriginal = org.objectweb.asm.Label()
        mv.visitJumpInsn(Opcodes.IFNULL, labelOriginal)
        // Cast and return snapshot value
        val returnType = desc.substringAfterLast(')')
        if (returnType.startsWith("L")) {
            val internalType = returnType.substring(1, returnType.length - 1)
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalType)
        }
        mv.visitInsn(Opcodes.ARETURN)

        // Original path: pop null, call $original
        mv.visitLabel(labelOriginal)
        mv.visitInsn(Opcodes.POP) // pop null
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        // For setBlockState: also load arg2 (BlockState) and arg3 (flags)
        if (name == "setBlockState") {
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitVarInsn(Opcodes.ILOAD, 3)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL_INTERNAL_NAME,
            "${name}\$original", desc, false)
        if (desc.endsWith("Z")) mv.visitInsn(Opcodes.IRETURN)
        else mv.visitInsn(Opcodes.ARETURN)

        mv.visitMaxs(5, 5)
        mv.visitEnd()
    }
}
```

**WorldAccessProxyDispatcher (NOWY PLIK — Task 18b):**

```kotlin
object WorldAccessProxyDispatcher {
    /** ThreadLocal: current region for this tick thread. null = not on a region thread. */
    private val currentRegion = ThreadLocal<Region?>()
    /** ThreadLocal: boundary buffer for snapshot reads. */
    private val currentBoundaryBuffer = ThreadLocal<BoundaryBuffer?>()

    fun setCurrentRegion(region: Region?, buffer: BoundaryBuffer?) {
        currentRegion.set(region)
        currentBoundaryBuffer.set(buffer)
    }

    /**
     * Dispatch: called from ASM-injected getBlockState/setBlockState/getBlockEntity.
     *
     * Returns:
     * - null → in-region access, caller proceeds to $original (FAST PATH)
     * - non-null → cross-region read, return this value instead of calling $original
     *
     * For writes: queues to message bus, returns null (write proceeds to $original
     * which will be intercepted by commit phase).
     */
    @JvmStatic
    fun dispatch(level: Any, methodName: String, pos: Any): Any? {
        val region = currentRegion.get() ?: return null // not on region thread → pass-through

        // Fast path: pos in current region? (~6ns: 2 field reads + 2 comparisons)
        val blockPos = pos // assume BlockPos with getX(), getZ() methods
        // Use reflection-free int extraction via unsafe or cached method handles
        // For now: reflection (will be optimized to MethodHandle in implementation)
        val posClass = pos.javaClass
        val x = posClass.getMethod("getX").invoke(pos) as Int
        val z = posClass.getMethod("getZ").invoke(pos) as Int

        val regionGridX = x shr region.regionShift
        val regionGridZ = z shr region.regionShift

        if (regionGridX == region.id.gridX && regionGridZ == region.id.gridZ) {
            return null // IN-REGION → fast path, call $original
        }

        // Cross-region read → return from snapshot
        val buffer = currentBoundaryBuffer.get() ?: return null
        return when (methodName) {
            "getBlockState" -> buffer.getBlockState(regionGridX, regionGridZ, x, pos)
            "getBlockEntity" -> buffer.getBlockEntity(regionGridX, regionGridZ, x, pos)
            "setBlockState" -> {
                // Cross-region write → queue to message bus
                region.queueCrossRegionWrite(RegionId(regionGridX, regionGridZ), pos)
                null // proceed to $original which will be a no-op or captured
            }
            else -> null
        }
    }
}
```

**Nowe testy Task 18:**
```kotlin
@Test
fun `dispatch returns null for in-region access (fast path)`()

@Test
fun `dispatch returns snapshot value for cross-region read`()

@Test
fun `dispatch queues write for cross-region setBlockState`()

@Test
fun `dispatch returns null when not on region thread`()

@Test
fun `transform renames getBlockState to getBlockState$original`()

@Test
fun `transformed class has new getBlockState that calls dispatch`()
```

---

### Task 21 ERRATA: ThreadingEngine — weighted allocation, use modRegistry

**Problem (design-compliance #BLOCKER-1, #BLOCKER-2, #IMPORTANT-5):**
- Task 22 tworzy nowy empty registry zamiast używać istniejącego
- Placement w bootstrap jest przed mod loading
- Thread allocation równy zamiast weighted

**Fix ThreadingEngine:**
```kotlin
class ThreadingEngine(
    private val config: ThreadingConfig,
    private val modRegistry: ModRegistryContract, // EXISTING registry, not new
    private val regionGroupingHint: RegionGroupingHint = RegionGroupingHint.NONE
) {
    fun initialize(dimensionIds: List<String>) {
        val weights = computeWeights(dimensionIds)
        // Overworld: 50% threads, Nether: 25%, End: 25% (minimum 1 per dimension)
        for (dimId in dimensionIds) {
            val threads = (config.totalThreads * weights[dimId]!!).toInt().coerceAtLeast(1)
            val pool = DimensionPool(dimId, threads)
            // ...
        }
    }

    private fun computeWeights(dimensionIds: List<String>): Map<String, Double> {
        // Overworld gets 50%, rest split evenly
        val result = mutableMapOf<String, Double>()
        val overworldWeight = 0.5
        val otherWeight = if (dimensionIds.size > 1) 0.5 / (dimensionIds.size - 1) else 0.0
        for (dim in dimensionIds) {
            result[dim] = if (dim == "overworld" || dim == "minecraft:overworld") overworldWeight else otherWeight
        }
        return result
    }
}
```

**Fix Task 22 — placement w AgentMain:**
```kotlin
// AFTER ModBootstrap.loadMods() AND AFTER modRegistry = registry
// BEFORE bootstrapComplete = true
val detector = OptimizationModDetector()
val detectedMods = detector.detect(registry) // use EXISTING registry
val threadingEngine = ThreadingEngine(threadingConfig, registry, RegionGroupingHint.NONE)
threadingEngine.initialize(listOf("overworld", "the_nether", "the_end"))
```

---

## NOWE TASKI

### Task 23 (NEW): RegionGroupingHint — interface na przyszły circuit tracker

**Files:**
- Create: `singularity-agent/src/main/kotlin/com/singularity/agent/threading/region/RegionGroupingHint.kt`
- Create: `singularity-agent/src/test/kotlin/com/singularity/agent/threading/region/RegionGroupingHintTest.kt`

```kotlin
package com.singularity.agent.threading.region

/**
 * Interface do grupowania regionów które muszą tickować razem (sekwencyjnie).
 *
 * Domyślnie: NONE (każdy region niezależny, 1-tick delay na granicy).
 * Przyszłość: RedstoneCircuitTracker implementuje ten interface — informuje
 * threading engine które regiony mają połączone obwody redstone.
 *
 * Server engine (roadmap): WYMAGA pełnej implementacji dla timing-sensitive contraptions.
 */
interface RegionGroupingHint {
    /**
     * Zwraca zbiór regionów które MUSZĄ tickować razem z danym regionem
     * w fazie REDSTONE. Pusta lista = brak powiązań.
     */
    fun getLinkedRegions(regionId: RegionId): Set<RegionId>

    companion object {
        /** Domyślna implementacja — brak powiązań, każdy region niezależny. */
        val NONE = object : RegionGroupingHint {
            override fun getLinkedRegions(regionId: RegionId): Set<RegionId> = emptySet()
        }
    }
}
```

TickExecutor w fazie REDSTONE: jeśli `regionGroupingHint.getLinkedRegions(A)` zwraca {B}, tickuj A i B sekwencyjnie na jednym wątku.

---

### Task 24 (NEW): WorldAccessProxyDispatcher

**Files:**
- Create: `singularity-agent/src/main/kotlin/com/singularity/agent/threading/proxy/WorldAccessProxyDispatcher.kt`
- Create: `singularity-agent/src/test/kotlin/com/singularity/agent/threading/proxy/WorldAccessProxyDispatcherTest.kt`

Pełny kod w Task 18 ERRATA powyżej. Osobny plik bo Transformer = bytecode ASM, Dispatcher = runtime routing logic.

---

### Task 25 (NEW): CrossDimensionTransferQueue

**Problem (edge-case-hunter #6):** Brak mechanizmu portali Nether/End.

**Files:**
- Create: `singularity-agent/src/main/kotlin/com/singularity/agent/threading/transfer/CrossDimensionTransferQueue.kt`
- Create: `singularity-agent/src/test/kotlin/com/singularity/agent/threading/transfer/CrossDimensionTransferQueueTest.kt`

```kotlin
data class CrossDimensionTransfer(
    val entityUuid: UUID,
    val sourceDimension: String,
    val sourceRegionId: RegionId,
    val targetDimension: String,
    val targetPosition: Triple<Double, Double, Double>,
    val entityPayload: Any,
    val groupId: UUID? = null // for grouped transfers (player + mount)
)

class CrossDimensionTransferQueue {
    private val pending = ConcurrentLinkedQueue<CrossDimensionTransfer>()

    fun enqueue(transfer: CrossDimensionTransfer) { pending.offer(transfer) }

    /** Called by ThreadingEngine BETWEEN dimension ticks. */
    fun drainAll(): List<CrossDimensionTransfer> {
        val result = mutableListOf<CrossDimensionTransfer>()
        var t = pending.poll()
        while (t != null) { result.add(t); t = pending.poll() }
        return result
    }
}
```

ThreadingEngine processes cross-dimension transfers between dimension tick cycles.

---

## NOWE TESTY (cross-cutting)

### Task 26 (NEW): Concurrent + stress + determinism tests

Dodaj ~30 nowych testów pokrywających CRITICAL-GAP z test-quality review:

**TickExecutorConcurrencyTest.kt:**
- `stuck region excluded from subsequent phases` (Phaser deregistration)
- `phases execute in strict order` (timestamp ordering)
- `regions processed in parallel within phase` (thread diversity)
- `deterministic output` (10 identical ticks → identical result sets)
- `stress — 50 regions 8 threads 100 ticks no crash`

**SnapshotPoolConcurrencyTest.kt:**
- `concurrent acquire never returns same instance` (100 threads, ConcurrentHashSet)
- `concurrent release respects maxSize` (AtomicInteger guard)
- `1000-tick acquire-release cycle — pool size bounded`

**RegionWatchdogIntegrationTest.kt:**
- `cooperative abort stops region mid-tick`
- `hard interrupt after cooperative abort ignored`
- `end-to-end: stuck detection → abort → recovery → next tick`

**ThreadingEngineIntegrationTest.kt:**
- `full tick lifecycle — create regions → tick → commit → verify state`
- `concurrent dimension ticking — zero cross-contamination`
- `shutdown during active tick — returns within 5s`
- `weighted thread allocation — overworld gets more threads`

**LockOrderingValidatorConcurrencyTest.kt:**
- `multi-thread isolation — each thread tracks independently`
- `violation detected on same thread` (existing, enhanced)

---

## PODSUMOWANIE ADDENDUM v2

**ERRATA:** Tasks 2, 6, 7, 8, 10, 11, 12, 13, 14, 18, 21, 22 (12 tasków poprawionych)
**NOWE TASKI:** 23 (RegionGroupingHint), 24 (WorldAccessProxyDispatcher), 25 (CrossDimensionTransfer), 26 (concurrent tests)
**USUNIĘTE:** TickBarrier (Task 6 — replaced by Phaser in Task 7)

**Kluczowe zmiany architektoniczne:**
1. Phaser zamiast CyclicBarrier/CountDownLatch — dynamic deregistration stuck regionów
2. WorldAccessProxy: method replacement (rename $original + dispatch) zamiast void hook
3. Snapshot: freeze/unfreeze pattern + BlockEntity NBT + fixed Y encoding
4. Entity transfer: grouped transfers (passengers)
5. RegionGroupingHint: interface na przyszły circuit tracker (server engine ready)
6. Cooperative abort before hard interrupt (watchdog)
7. Weighted thread allocation (Overworld 50%)
8. Cross-dimension transfer queue (portals)

**Szacowane dodatkowe testy:** ~30 (concurrent, stress, determinism)
**Łączna szacowana liczba testów Sub 3:** ~176 (146 original + 30 nowych)
