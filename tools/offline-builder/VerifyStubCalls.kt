package de.rdoe.weeklydjshows.tools

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

private data class MethodRef(
    val owner: String,
    val name: String,
    val descriptor: String,
    val opcode: Int = Opcodes.INVOKESTATIC,
)

/** Checks dependency method calls in compiled classes against the methods present in a stub jar. */
object VerifyStubCalls {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: VerifyStubCalls <compiled-class-directory> <stub.jar>" }
        val compiled = File(args[0])
        val stubJar = File(args[1])
        val available = linkedSetOf<MethodRef>()
        val stubOwners = linkedSetOf<String>()
        JarFile(stubJar).use { jar ->
            jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                jar.getInputStream(entry).use { input ->
                    ClassReader(input).accept(object : ClassVisitor(Opcodes.ASM9) {
                        private lateinit var owner: String

                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?,
                        ) {
                            owner = name
                            stubOwners += name
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            available += MethodRef(owner, name, descriptor)
                            return null
                        }
                    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
        }

        val calls = linkedSetOf<MethodRef>()
        compiled.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
            ClassReader(classFile.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (
                            opcode == Opcodes.INVOKESTATIC &&
                            owner in stubOwners &&
                            !owner.startsWith("de/rdoe/weeklydjshows/")
                        ) {
                            calls += MethodRef(owner, name, descriptor, opcode)
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }

        val missing = calls.filterNot { call ->
            available.any { method ->
                method.owner == call.owner &&
                    method.name == call.name &&
                    method.descriptor == call.descriptor
            }
        }
        println("Dependency calls checked: ${calls.size}")
        println("Missing exact methods: ${missing.size}")
        missing.sortedWith(compareBy(MethodRef::owner, MethodRef::name, MethodRef::descriptor))
            .forEach { println("${it.owner}->${it.name}${it.descriptor}") }
        if (missing.isNotEmpty()) error("Compiled code calls dependency methods absent from the APK")
    }
}
