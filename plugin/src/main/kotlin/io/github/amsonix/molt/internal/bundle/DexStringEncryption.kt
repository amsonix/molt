package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.util.SeedRandom
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x
import org.jf.dexlib2.builder.instruction.BuilderInstruction31c
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File

/**
 * post-R8 DEX 字符串加密配置。
 *
 * [encryptableR8Types]：R8 改名后属于工程包（projectPackagePrefixes）的类描述符集合，
 * 由 [StringEncryptConfigFactory] 按 R8 mapping 的 original 名计算。
 */
internal data class DexStringEncryptionConfig(
    val fogDescriptor: String,
    val key: IntArray,
    val encryptableR8Types: Set<String>,
    val projectPackagePrefixes: List<String>,
    val excludeClassPatterns: List<String>,
    val keepStrings: List<Regex>,
    val minLength: Int = 3,
)

/**
 * post-R8 DEX 字符串加密：将 const-string 替换为
 * `const-string-jumbo` + `invoke-static Fog.d` + `move-result-object`（复用同一寄存器）。
 * 与组件/View 改名共用同一趟 dexlib2 重建（见 [DexBinaryPatchWriter]）。
 */
internal object DexStringEncryptor {

    /** 类全名形态（反射/Intent 组件名等，不可加密）。 */
    private val CLASS_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$""")

    /** dex 描述符形态（`Lcom/x/y;`）。 */
    private val DESCRIPTOR = Regex("""^L[A-Za-z0-9_/$]+;$""")

    /** 纯标识符（枚举名、字段名、JSON key 等，保守跳过）。 */
    private val IDENTIFIER = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")

    fun deriveKey(seed: Int): IntArray {
        val random = SeedRandom.create(seed, "string-fog")
        // 掩码 16 位：与 char 逐位 XOR 后 toChar 截断，否则高位污染导致不可逆。
        return IntArray(4) { random.nextInt() and 0xFFFF }
    }

    fun fogPackagePrefix(applicationId: String): String = "$applicationId.shell.fog"

    fun fogDescriptor(applicationId: String): String =
        "L${fogPackagePrefix(applicationId).replace('.', '/')}/Fog;"

    fun buildFogSource(applicationId: String, key: IntArray): String = """
        package ${fogPackagePrefix(applicationId)};

        public final class Fog {
            private static final int[] KEY = {${key.joinToString()}};

            public static String d(String s) {
                if (s == null) {
                    return null;
                }
                char[] chars = s.toCharArray();
                int salt = chars.length & 0xFF;
                for (int i = 0; i < chars.length; i++) {
                    chars[i] ^= (char) (KEY[i & 3] ^ salt ^ (i & 0xFF));
                }
                return new String(chars);
            }
        }
    """.trimIndent()

    /** XOR 自逆：解密 = 再次应用同变换。盐由密文长度派生（解密端可复算），相同明文 -> 相同密文（保留 dex 字符串池去重）。 */
    fun encrypt(plain: String, key: IntArray): String {
        val salt = plain.length and 0xFF
        val chars = plain.toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor key[i and 3] xor salt xor (i and 0xFF)).toChar()
        }
        return String(chars)
    }

    fun shouldEncrypt(string: String, config: DexStringEncryptionConfig): Boolean {
        if (string.isEmpty() || string.length < config.minLength) return false
        if (config.keepStrings.any { it.containsMatchIn(string) }) return false
        if (CLASS_NAME.matches(string) || DESCRIPTOR.matches(string)) return false
        if (string.contains('/')) return false
        if (IDENTIFIER.matches(string)) return false
        return true
    }

    /** 类是否参与加密：R8 改名后属工程包（[encryptableR8Types]），或未被 R8 改名且仍带工程包前缀（keep 类）。 */
    fun shouldEncryptClass(classType: String, config: DexStringEncryptionConfig): Boolean {
        if (config.encryptableR8Types.contains(classType)) return true
        val dotName = classType.removePrefix("L").removeSuffix(";").replace('/', '.')
        for (prefix in config.projectPackagePrefixes) {
            if (!dotName.startsWith(prefix)) continue
            return !excluded(dotName, config.excludeClassPatterns)
        }
        return false
    }

    /** classDef 为 DexRewriter 改写后的（lazy，类型已改名）；只对需加密的类做字符串替换。 */
    fun rewriteClassStrings(classDef: ClassDef, config: DexStringEncryptionConfig): ClassDef {
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

    private fun rewriteMethod(method: Method, config: DexStringEncryptionConfig): Method {
        val implementation = method.implementation ?: return method
        val rewritten = rewriteImplementation(implementation, config) ?: return method
        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            rewritten,
        )
    }

    private fun rewriteImplementation(
        implementation: MethodImplementation,
        config: DexStringEncryptionConfig,
    ): MethodImplementation? {
        // 用 dexlib2 builder 重写：构造时所有分支/switch/try/debug 偏移都已转为 Label 引用，
        // 插入指令后 getInstructions()/getTryBlocks() 触发 fixInstructions() 自动重算全部偏移。
        // 手工拼 Immutable 指令并同步平移偏移的方式（旧实现）会漏掉分支跳转目标，
        // 导致 "target dex pc 0xXX is not at instruction start" VerifyError。
        val mmi = MutableMethodImplementation(implementation)
        val fogMethod = ImmutableMethodReference(
            config.fogDescriptor,
            "d",
            listOf("Ljava/lang/String;"),
            "Ljava/lang/String;",
        )
        var changed = false
        val instructions = mmi.instructions
        for (index in instructions.indices.reversed()) {
            val instruction = instructions[index]
            if (instruction !is ReferenceInstruction || instruction.reference !is StringReference) continue
            val string = (instruction.reference as StringReference).string
            if (!shouldEncrypt(string, config)) continue
            val register = (instruction as OneRegisterInstruction).registerA
            mmi.replaceInstruction(
                index,
                BuilderInstruction31c(
                    Opcode.CONST_STRING_JUMBO,
                    register,
                    ImmutableStringReference(encrypt(string, config.key)),
                ),
            )
            // INVOKE_STATIC 35c 格式的寄存器只能编码 v0-v15（nibble）；
            // 原 const-string 寄存器可能 >= v16（16 位编码），此时必须用
            // invoke-static/range（3rc，short 寄存器），否则写入会报
            // "Invalid register: v16. Must be between v0 and v15"。
            if (register < 16) {
                mmi.addInstruction(
                    index + 1,
                    BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0, fogMethod),
                )
            } else {
                mmi.addInstruction(
                    index + 1,
                    BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, fogMethod),
                )
            }
            mmi.addInstruction(index + 2, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register))
            changed = true
        }
        return if (changed) mmi else null
    }

    private fun excluded(dotName: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->
            pattern.replace(".", "\\.").replace("*", ".*").toRegex().containsMatchIn(dotName)
        }
}

/** 由 R8 mapping 计算工程包下 R8 改名类的描述符集合（original 名匹配 prefix 且未被 exclude）。 */
internal object StringEncryptConfigFactory {

    fun buildEncryptableR8Types(
        r8MappingFile: File?,
        projectPackagePrefixes: List<String>,
        excludeClassPatterns: List<String>,
    ): Set<String> {
        if (r8MappingFile == null || !r8MappingFile.isFile) return emptySet()
        val result = linkedSetOf<String>()
        R8MappingAliasExpander.parseClassMappings(r8MappingFile).forEach { (original, r8Name) ->
            if (original.isBlank() || original == r8Name) return@forEach
            if (projectPackagePrefixes.none { original.startsWith(it) }) return@forEach
            if (excluded(original, excludeClassPatterns)) return@forEach
            result += "L${r8Name.replace('.', '/')};"
        }
        return result
    }

    private fun excluded(dotName: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->
            pattern.replace(".", "\\.").replace("*", ".*").toRegex().containsMatchIn(dotName)
        }
}
