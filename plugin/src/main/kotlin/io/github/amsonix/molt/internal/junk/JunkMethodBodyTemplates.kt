package io.github.amsonix.molt.internal.junk

import java.util.Random

/** Junk 方法体模板，借鉴 AndroidJunkCode 多样性但不引入 JavaPoet。 */
internal object JunkMethodBodyTemplates {

    fun appendUtilityMethod(
        out: StringBuilder,
        indent: String,
        methodName: String,
        random: Random,
        salt: Int,
    ) {
        when (random.nextInt(7)) {
            0 -> appendStaticDateMethod(out, indent, methodName)
            1 -> appendStaticHelloMethod(out, indent, methodName)
            else -> appendStaticIntMethod(out, indent, methodName, random, salt)
        }
    }

    fun appendStaticIntMethod(
        out: StringBuilder,
        indent: String,
        methodName: String,
        random: Random,
        salt: Int,
    ) {
        out.appendLine("${indent}public static int $methodName(int input) {")
        when (random.nextInt(5)) {
            0 -> appendXorBody(out, indent, salt, random)
            1 -> appendTimeBranchBody(out, indent)
            2 -> appendLoopSumBody(out, indent)
            3 -> appendTryCatchBody(out, indent)
            else -> appendHashMixBody(out, indent, salt)
        }
        out.appendLine("${indent}}")
        out.appendLine()
    }

    fun appendStaticDateMethod(
        out: StringBuilder,
        indent: String,
        methodName: String,
    ) {
        out.appendLine("${indent}public static java.util.Date $methodName() {")
        val body = indent + "    "
        out.appendLine("${body}return new java.util.Date();")
        out.appendLine("${indent}}")
        out.appendLine()
    }

    fun appendStaticHelloMethod(
        out: StringBuilder,
        indent: String,
        methodName: String,
    ) {
        out.appendLine("${indent}public static void $methodName(String[] args) {")
        val body = indent + "    "
        out.appendLine("${body}System.out.println(\"Hello\");")
        out.appendLine("${indent}}")
        out.appendLine()
    }

    fun appendInstanceVoidMethod(
        out: StringBuilder,
        indent: String,
        methodName: String,
        random: Random,
    ) {
        out.appendLine("${indent}private void $methodName() {")
        when (random.nextInt(3)) {
            0 -> appendTimeBranchBody(out, indent, "void")
            1 -> appendLoopSumBody(out, indent, "void")
            else -> appendTryCatchBody(out, indent, "void")
        }
        out.appendLine("${indent}}")
        out.appendLine()
    }

    private fun appendXorBody(out: StringBuilder, indent: String, salt: Int, random: Random) {
        val body = indent + "    "
        out.appendLine("${body}if (System.nanoTime() < 0L) {")
        out.appendLine("${body}    return new java.util.Random().nextInt();")
        out.appendLine("${body}}")
        out.appendLine("${body}int v = input ^ $salt ^ ${random.nextInt()};")
        out.appendLine("${body}return v + java.util.UUID.randomUUID().hashCode() % ${1 + random.nextInt(7)};")
    }

    private fun appendTimeBranchBody(out: StringBuilder, indent: String, returnType: String = "int") {
        val body = indent + "    "
        out.appendLine("${body}long now = System.currentTimeMillis();")
        out.appendLine("${body}if (System.currentTimeMillis() < now) {")
        out.appendLine("${body}    System.out.println(\"Time travelling, woo hoo!\");")
        out.appendLine("${body}} else if (System.currentTimeMillis() == now) {")
        out.appendLine("${body}    System.out.println(\"Time stood still!\");")
        out.appendLine("${body}} else {")
        out.appendLine("${body}    System.out.println(\"Ok, time still moving forward\");")
        out.appendLine("${body}}")
        if (returnType == "int") {
            out.appendLine("${body}return (int) (now & 0xFF);")
        }
    }

    private fun appendLoopSumBody(out: StringBuilder, indent: String, returnType: String = "int") {
        val body = indent + "    "
        out.appendLine("${body}int total = 0;")
        out.appendLine("${body}for (int i = 0; i < 10; i++) {")
        out.appendLine("${body}    total += i;")
        out.appendLine("${body}}")
        if (returnType == "int") {
            out.appendLine("${body}return total;")
        }
    }

    private fun appendTryCatchBody(out: StringBuilder, indent: String, returnType: String = "int") {
        val body = indent + "    "
        out.appendLine("${body}try {")
        out.appendLine("${body}    throw new Exception(\"Failed\");")
        out.appendLine("${body}} catch (Exception e) {")
        if (returnType == "int") {
            out.appendLine("${body}    return input ^ 1;")
        } else {
            out.appendLine("${body}    throw new RuntimeException(e);")
        }
        out.appendLine("${body}}")
    }

    private fun appendHashMixBody(out: StringBuilder, indent: String, salt: Int) {
        val body = indent + "    "
        out.appendLine("${body}return Integer.rotateLeft(input ^ $salt, ${1 + (salt and 3)});")
    }
}
