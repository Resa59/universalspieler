package de.rdoe.weeklydjshows.tools

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue
import com.android.tools.smali.dexlib2.iface.value.CharEncodedValue
import com.android.tools.smali.dexlib2.iface.value.DoubleEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue
import com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue
import com.android.tools.smali.dexlib2.iface.value.NullEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes as AsmOpcodes
import org.objectweb.asm.Type
import org.jetbrains.kotlin.metadata.jvm.JvmModuleProtoBuf
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.PackageParts
import org.jetbrains.kotlin.metadata.jvm.deserialization.serializeToByteArray
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Creates JVM signature stubs from the classes embedded in an APK's DEX files.
 *
 * The stubs are only a compiler class path. They deliberately contain no working
 * implementation; the real dependency implementations remain in the base APK.
 */
object DexStubJar {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: DexStubJar <output.jar> <classes.dex> [classes2.dex ...]"
        }
        val output = File(args[0])
        output.parentFile?.mkdirs()
        val classes = linkedMapOf<String, ClassDef>()
        args.drop(1).forEach { path ->
            val dex = DexFileFactory.loadDexFile(File(path), Opcodes.getDefault())
            dex.classes.forEach { classDef -> classes.putIfAbsent(classDef.type.toInternalName(), classDef) }
        }
        val packageParts = linkedMapOf<String, PackageParts>()
        JarOutputStream(output.outputStream().buffered()).use { jar ->
            classes.toSortedMap().forEach { (internalName, classDef) ->
                jar.putNextEntry(JarEntry("$internalName.class"))
                jar.write(classDef.toStubBytes(classes))
                jar.closeEntry()
                classDef.kotlinPackagePart()?.let { part ->
                    packageParts.getOrPut(part.packageName) { PackageParts(part.packageName) }
                        .addPart(part.internalName, part.multifileFacadeInternalName)
                }
            }
            val module = JvmModuleProtoBuf.Module.newBuilder().apply {
                packageParts.values.forEach { it.addTo(this) }
            }.build()
            jar.putNextEntry(JarEntry("META-INF/base.kotlin_module"))
            jar.write(module.serializeToByteArray(JvmMetadataVersion.INSTANCE, 0))
            jar.closeEntry()
        }
        println("Wrote ${classes.size} compiler stubs to ${output.absolutePath}")
    }
}

private data class KotlinPackagePart(
    val packageName: String,
    val internalName: String,
    val multifileFacadeInternalName: String?,
)

private fun ClassDef.kotlinPackagePart(): KotlinPackagePart? {
    val metadata = annotations.firstOrNull { it.type == "Lkotlin/Metadata;" } ?: return null
    val kind = metadata.elements
        .firstOrNull { it.name == "k" }
        ?.value
        ?.let { it as? IntEncodedValue }
        ?.value
        ?: return null
    if (kind != 2 && kind != 5) return null
    val internalName = type.toInternalName()
    val packageName = internalName.substringBeforeLast('/', "").replace('/', '.')
    val facade = if (kind == 5) {
        metadata.elements.firstOrNull { it.name == "xs" }
            ?.value
            ?.let { it as? StringEncodedValue }
            ?.value
            ?.replace('.', '/')
            ?.takeIf(String::isNotBlank)
    } else {
        null
    }
    return KotlinPackagePart(packageName, internalName, facade)
}

private fun ClassDef.toStubBytes(classes: Map<String, ClassDef>): ByteArray {
    val signature = annotations.signature()
    val interfaces = interfaces.map(String::toInternalName).toTypedArray()
    val classAccess = accessFlags.jvmClassAccess()
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(
        AsmOpcodes.V1_8,
        classAccess,
        type.toInternalName(),
        signature,
        superclass?.toInternalName(),
        interfaces,
    )

    val internalName = type.toInternalName()
    if ('$' in internalName) {
        val outerName = internalName.substringBeforeLast('$')
        writer.visitInnerClass(
            internalName,
            outerName,
            innerClassName() ?: internalName.substringAfterLast('$').asInnerSimpleName(),
            innerClassAccessFlags(),
        )
    }
    classes.asSequence()
        .filter { (candidate, _) ->
            candidate.startsWith("$internalName$") && candidate.substringBeforeLast('$') == internalName
        }
        .forEach { (candidate, child) ->
            writer.visitInnerClass(
                candidate,
                internalName,
                child.innerClassName() ?: candidate.substringAfterLast('$').asInnerSimpleName(),
                child.innerClassAccessFlags(),
            )
        }

    annotations.forEach { annotation ->
        if (!annotation.isDexStructuralAnnotation()) {
            annotation.writeTo(writer.visitAnnotation(annotation.type, annotation.isRuntimeVisible()))
        }
    }

    fields.forEach { field -> writer.writeField(field) }
    val hasDesugaredDefaults = classAccess and AsmOpcodes.ACC_INTERFACE != 0 &&
        (classes.containsKey("${internalName}\$-CC") || classes.containsKey("${internalName}\$DefaultImpls"))
    methods.forEach { method -> writer.writeMethod(method, hasDesugaredDefaults) }
    writer.visitEnd()
    return writer.toByteArray()
}

private fun ClassWriter.writeField(field: Field) {
    val visitor = visitField(
        field.accessFlags.jvmMemberAccess(),
        field.name,
        field.type,
        field.annotations.signature(),
        field.initialValue?.toJvmConstant(),
    )
    field.annotations.forEach { annotation ->
        if (!annotation.isDexStructuralAnnotation()) {
            annotation.writeTo(visitor.visitAnnotation(annotation.type, annotation.isRuntimeVisible()))
        }
    }
    visitor.visitEnd()
}

private fun ClassWriter.writeMethod(method: Method, hasDesugaredDefaults: Boolean) {
    val descriptor = Type.getMethodDescriptor(
        Type.getType(method.returnType),
        *method.parameterTypes.map { Type.getType(it.toString()) }.toTypedArray(),
    )
    val exceptions = method.annotations.throwsTypes().takeIf(List<String>::isNotEmpty)?.toTypedArray()
    val originalAccess = method.accessFlags.jvmMemberAccess()
    val access = if (hasDesugaredDefaults && originalAccess and AsmOpcodes.ACC_ABSTRACT != 0) {
        originalAccess and AsmOpcodes.ACC_ABSTRACT.inv()
    } else {
        originalAccess
    }
    val visitor = visitMethod(
        access,
        method.name,
        descriptor,
        method.annotations.signature(),
        exceptions,
    )
    method.annotations.forEach { annotation ->
        if (!annotation.isDexStructuralAnnotation()) {
            annotation.writeTo(visitor.visitAnnotation(annotation.type, annotation.isRuntimeVisible()))
        }
    }
    method.parameters.forEachIndexed { index, parameter ->
        parameter.annotations.forEach { annotation ->
            if (!annotation.isDexStructuralAnnotation()) {
                annotation.writeTo(
                    visitor.visitParameterAnnotation(index, annotation.type, annotation.isRuntimeVisible()),
                )
            }
        }
    }

    if (access and (AsmOpcodes.ACC_ABSTRACT or AsmOpcodes.ACC_NATIVE) == 0) {
        visitor.visitCode()
        when {
            method.name == "<init>" -> visitor.visitInsn(AsmOpcodes.RETURN)
            method.returnType == "V" -> visitor.visitInsn(AsmOpcodes.RETURN)
            method.returnType in setOf("Z", "B", "C", "S", "I") -> {
                visitor.visitInsn(AsmOpcodes.ICONST_0)
                visitor.visitInsn(AsmOpcodes.IRETURN)
            }
            method.returnType == "J" -> {
                visitor.visitInsn(AsmOpcodes.LCONST_0)
                visitor.visitInsn(AsmOpcodes.LRETURN)
            }
            method.returnType == "F" -> {
                visitor.visitInsn(AsmOpcodes.FCONST_0)
                visitor.visitInsn(AsmOpcodes.FRETURN)
            }
            method.returnType == "D" -> {
                visitor.visitInsn(AsmOpcodes.DCONST_0)
                visitor.visitInsn(AsmOpcodes.DRETURN)
            }
            else -> {
                visitor.visitInsn(AsmOpcodes.ACONST_NULL)
                visitor.visitInsn(AsmOpcodes.ARETURN)
            }
        }
        visitor.visitMaxs(0, 0)
    }
    visitor.visitEnd()
}

private fun Annotation.writeTo(visitor: AnnotationVisitor?) {
    if (visitor == null) return
    elements.forEach { element -> visitor.writeElement(element) }
    visitor.visitEnd()
}

private fun AnnotationVisitor.writeElement(element: AnnotationElement) {
    writeEncoded(element.name, element.value)
}

private fun AnnotationVisitor.writeEncoded(name: String?, value: EncodedValue) {
    when (value) {
        is BooleanEncodedValue -> visit(name, value.value)
        is ByteEncodedValue -> visit(name, value.value)
        is CharEncodedValue -> visit(name, value.value)
        is ShortEncodedValue -> visit(name, value.value)
        is IntEncodedValue -> visit(name, value.value)
        is LongEncodedValue -> visit(name, value.value)
        is FloatEncodedValue -> visit(name, value.value)
        is DoubleEncodedValue -> visit(name, value.value)
        is StringEncodedValue -> visit(name, value.value)
        is TypeEncodedValue -> visit(name, Type.getType(value.value))
        is EnumEncodedValue -> visitEnum(name, value.value.definingClass, value.value.name)
        is AnnotationEncodedValue -> {
            val child = visitAnnotation(name, value.type)
            value.elements.forEach { child.writeElement(it) }
            child.visitEnd()
        }
        is ArrayEncodedValue -> {
            val array = visitArray(name)
            value.value.forEach { array.writeEncoded(null, it) }
            array.visitEnd()
        }
        is NullEncodedValue -> visit(name, null)
        else -> Unit
    }
}

private fun Set<out Annotation>.signature(): String? =
    firstOrNull { it.type == "Ldalvik/annotation/Signature;" }
        ?.elements
        ?.firstOrNull { it.name == "value" }
        ?.value
        ?.let { it as? ArrayEncodedValue }
        ?.value
        ?.joinToString(separator = "") { (it as StringEncodedValue).value }

private fun Set<out Annotation>.throwsTypes(): List<String> =
    firstOrNull { it.type == "Ldalvik/annotation/Throws;" }
        ?.elements
        ?.firstOrNull { it.name == "value" }
        ?.value
        ?.let { it as? ArrayEncodedValue }
        ?.value
        ?.mapNotNull { (it as? TypeEncodedValue)?.value?.toInternalName() }
        .orEmpty()

private fun Annotation.isDexStructuralAnnotation(): Boolean =
    type.startsWith("Ldalvik/annotation/")

private fun Annotation.isRuntimeVisible(): Boolean = visibility == 1

private fun ClassDef.innerClassAccessFlags(): Int =
    annotations.firstOrNull { it.type == "Ldalvik/annotation/InnerClass;" }
        ?.elements
        ?.firstOrNull { it.name == "accessFlags" }
        ?.value
        ?.let { it as? IntEncodedValue }
        ?.value
        ?.jvmInnerClassAccess()
        ?: accessFlags.jvmInnerClassAccess()

private fun ClassDef.innerClassName(): String? =
    annotations.firstOrNull { it.type == "Ldalvik/annotation/InnerClass;" }
        ?.elements
        ?.firstOrNull { it.name == "name" }
        ?.value
        ?.let { it as? StringEncodedValue }
        ?.value

private fun EncodedValue.toJvmConstant(): Any? = when (this) {
    is BooleanEncodedValue -> value
    is ByteEncodedValue -> value
    is CharEncodedValue -> value
    is ShortEncodedValue -> value
    is IntEncodedValue -> value
    is LongEncodedValue -> value
    is FloatEncodedValue -> value
    is DoubleEncodedValue -> value
    is StringEncodedValue -> value
    else -> null
}

private fun Int.jvmClassAccess(): Int =
    (this and 0xffff) or if (this and AsmOpcodes.ACC_INTERFACE == 0) AsmOpcodes.ACC_SUPER else 0

private fun Int.jvmInnerClassAccess(): Int =
    (this and 0xffff) and AsmOpcodes.ACC_SUPER.inv()

private fun Int.jvmMemberAccess(): Int = this and 0xffff

private fun String.toInternalName(): String = removePrefix("L").removeSuffix(";")

private fun String.asInnerSimpleName(): String? =
    takeIf { it.isNotEmpty() && Character.isJavaIdentifierStart(it.first()) && all(Character::isJavaIdentifierPart) }
