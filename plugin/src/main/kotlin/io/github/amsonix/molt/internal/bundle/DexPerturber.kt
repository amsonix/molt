package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.util.SeedRandom
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod

/**
 * post-R8 DEX 控制流扰动（垃圾指令注入）。
 *
 * 用 dexlib2 builder 在方法体随机位置注入 `nop`（确定性 seed）：
 * nop 无副作用、不改变控制流，builder 自动重算分支/switch/try 偏移，
 * 因此任意插入点安全；每构建 nop 数量与位置由 seed 派生（跨包不同）。
 */
internal data class DexPerturbationConfig(
    val seed: Int,
    val intensity: String = "light",
) {
    val nopRange: IntRange = when (intensity) {
        "medium" -> 3..8
        "heavy" -> 8..20
        else -> 1..3
    }
}

internal object DexPerturber {

    fun rewriteClass(classDef: ClassDef, config: DexPerturbationConfig): ClassDef {
        val direct = classDef.directMethods.map { rewriteMethod(it, config) }
        val virtual = classDef.virtualMethods.map { rewriteMethod(it, config) }
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            direct,
            virtual,
        )
    }

    private fun rewriteMethod(method: Method, config: DexPerturbationConfig): Method {
        val implementation = method.implementation ?: return method
        val random = SeedRandom.create(
            config.seed,
            "dex-perturb-${method.definingClass}-${method.name}-${method.parameters.size}",
        )
        val mmi = MutableMethodImplementation(implementation)
        val instructions = mmi.instructions
        if (instructions.isEmpty()) return method
        val nopCount = config.nopRange.random(random)
        repeat(nopCount) {
            val index = random.nextInt(instructions.size)
            mmi.addInstruction(index, BuilderInstruction10x(Opcode.NOP))
        }
        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            mmi,
        )
    }
}

/** kotlin.random.Random 适配 java.util.Random（SeedRandom 返回类型）。 */
private fun IntRange.random(random: java.util.Random): Int =
    if (isEmpty()) first else first + random.nextInt(last - first + 1)
