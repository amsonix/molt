package io.github.amsonix.molt.internal.util

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private fun MethodHandle.invokeWithProvider(target: Any, provider: Provider<*>): Any? =
    invoke(target, provider)

private const val UPLOAD_MAPPING_FILE_TASK = "UploadMappingFileTask"

/** Gradle 运行时 task 常为 `UploadMappingFileTask_Decorated`，需沿继承链识别。 */
internal fun isCrashlyticsUploadMappingFileTask(task: Task): Boolean =
    findCrashlyticsUploadMappingFileTaskClass(task) != null

internal fun findCrashlyticsUploadMappingFileTaskClass(task: Task): Class<out Task>? {
    var current: Class<*>? = task.javaClass
    while (current != null && current != Any::class.java) {
        if (current.simpleName == UPLOAD_MAPPING_FILE_TASK ||
            current.name.endsWith(".$UPLOAD_MAPPING_FILE_TASK")
        ) {
            @Suppress("UNCHECKED_CAST")
            return current as Class<out Task>
        }
        current = current.superclass
    }
    return null
}

private fun Class<out Task>.findInstanceMethod(name: String): Method? {
    var current: Class<*>? = this
    while (current != null && current != Any::class.java) {
        runCatching { current.getMethod(name) }.getOrNull()?.let { return it }
        current = current.superclass
    }
    return null
}

private fun Class<out Task>.findInstanceMethod(
    predicate: (Method) -> Boolean,
): Method? {
    var current: Class<*>? = this
    while (current != null && current != Any::class.java) {
        current.methods.firstOrNull(predicate)?.let { return it }
        current = current.superclass
    }
    return null
}

/**
 * Crashlytics [UploadMappingFileTask] 接线绑定。
 *
 * 发布产物不能引用 Firebase 类（compileOnly 不进 runtime classpath），
 * 因此在首次遇到 task 类型时解析 getter 并缓存 [MethodHandle]；
 * setter 在首次接线时按 property 运行时类型解析（与旧版反射接线一致）。
 */
internal sealed class CrashlyticsUploadTaskBinding {

    abstract fun applyMapping(
        task: Task,
        mappingFile: Provider<RegularFile>,
        project: Project,
    )

    companion object {
        private val cache = ConcurrentHashMap<Class<out Task>, CrashlyticsUploadTaskBinding?>()
        private val lookup = MethodHandles.lookup()

        fun resolve(task: Task): CrashlyticsUploadTaskBinding? {
            val taskClass = findCrashlyticsUploadMappingFileTaskClass(task) ?: return null
            return cache.getOrPut(taskClass) { createBinding(taskClass) }
        }

        private fun createBinding(taskClass: Class<out Task>): CrashlyticsUploadTaskBinding? =
            createMergedMappingFileBinding(taskClass)
                ?: createMergedMappingFileTaskSetterBinding(taskClass)
                ?: createMappingFileProviderSetterBinding(taskClass)
                ?: createMappingFileProviderBinding(taskClass)

        private fun createMergedMappingFileBinding(taskClass: Class<out Task>): CrashlyticsUploadTaskBinding? {
            val getter = taskClass.findInstanceMethod("getMergedMappingFile") ?: return null
            return MergedMappingFileBinding(getterHandle = lookup.unreflect(getter))
        }

        private fun createMergedMappingFileTaskSetterBinding(taskClass: Class<out Task>): CrashlyticsUploadTaskBinding? {
            val setter = taskClass.findInstanceMethod { method ->
                method.name == "setMergedMappingFile" &&
                    method.parameterCount == 1 &&
                    Provider::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return null
            return MergedMappingFileTaskSetterBinding(setterHandle = lookup.unreflect(setter))
        }

        private fun createMappingFileProviderBinding(taskClass: Class<out Task>): CrashlyticsUploadTaskBinding? {
            val getter = taskClass.findInstanceMethod("getMappingFileProvider") ?: return null
            return MappingFileProviderBinding(getterHandle = lookup.unreflect(getter))
        }

        private fun createMappingFileProviderSetterBinding(taskClass: Class<out Task>): CrashlyticsUploadTaskBinding? {
            val setter = taskClass.findInstanceMethod { method ->
                method.name == "setMappingFileProvider" &&
                    method.parameterCount == 1 &&
                    Provider::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return null
            return MappingFileProviderSetterBinding(setterHandle = lookup.unreflect(setter))
        }

        internal fun findProviderSetterMethod(propertyClass: Class<*>): Method? {
            val pending = ArrayDeque<Class<*>>()
            val visited = mutableSetOf<Class<*>>()
            pending.add(propertyClass)
            while (pending.isNotEmpty()) {
                val type = pending.removeFirst()
                if (!visited.add(type)) continue
                type.methods.firstOrNull(::isProviderSetter)?.let { return it }
                type.interfaces.forEach { pending.add(it) }
                type.superclass?.let { pending.add(it) }
            }
            return null
        }

        private fun isProviderSetter(method: Method): Boolean =
            method.name == "set" &&
                method.parameterCount == 1 &&
                Provider::class.java.isAssignableFrom(method.parameterTypes[0])
    }

    private class MergedMappingFileBinding(
        private val getterHandle: MethodHandle,
    ) : CrashlyticsUploadTaskBinding() {
        private val setterHandles = ConcurrentHashMap<Class<*>, MethodHandle>()

        override fun applyMapping(
            task: Task,
            mappingFile: Provider<RegularFile>,
            project: Project,
        ) {
            val property = getterHandle.invoke(task)
                ?: error("mergedMappingFile is null on ${task.path}")
            val setter = setterHandles.getOrPut(property.javaClass) {
                val method = findProviderSetterMethod(property.javaClass)
                    ?: error("cannot set mergedMappingFile on ${property.javaClass.name}")
                lookup.unreflect(method)
            }
            setter.invokeWithProvider(property, mappingFile)
        }
    }

    private class MergedMappingFileTaskSetterBinding(
        private val setterHandle: MethodHandle,
    ) : CrashlyticsUploadTaskBinding() {
        override fun applyMapping(
            task: Task,
            mappingFile: Provider<RegularFile>,
            project: Project,
        ) {
            setterHandle.invokeWithProvider(task, mappingFile)
        }
    }

    private class MappingFileProviderBinding(
        private val getterHandle: MethodHandle,
    ) : CrashlyticsUploadTaskBinding() {
        private val setterHandles = ConcurrentHashMap<Class<*>, MethodHandle>()

        override fun applyMapping(
            task: Task,
            mappingFile: Provider<RegularFile>,
            project: Project,
        ) {
            val property = getterHandle.invoke(task)
                ?: error("mappingFileProvider is null on ${task.path}")
            val fileCollectionProvider = mappingFile.map { regularFile -> project.files(regularFile.asFile) }
            val setter = setterHandles.getOrPut(property.javaClass) {
                val method = findProviderSetterMethod(property.javaClass)
                    ?: error("cannot set mappingFileProvider on ${property.javaClass.name}")
                lookup.unreflect(method)
            }
            setter.invokeWithProvider(property, fileCollectionProvider)
        }
    }

    private class MappingFileProviderSetterBinding(
        private val setterHandle: MethodHandle,
    ) : CrashlyticsUploadTaskBinding() {
        override fun applyMapping(
            task: Task,
            mappingFile: Provider<RegularFile>,
            project: Project,
        ) {
            val fileCollectionProvider = mappingFile.map { regularFile -> project.files(regularFile.asFile) }
            setterHandle.invokeWithProvider(task, fileCollectionProvider)
        }
    }
}
