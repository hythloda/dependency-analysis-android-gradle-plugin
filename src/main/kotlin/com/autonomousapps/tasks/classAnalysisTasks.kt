@file:Suppress("UnstableApiUsage")

package com.autonomousapps.tasks

import com.autonomousapps.TASK_GROUP_DEP
import com.autonomousapps.internal.ClassSetReader
import com.autonomousapps.internal.JarReader
import com.autonomousapps.internal.utils.log
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

abstract class ClassAnalysisTask(private val objects: ObjectFactory) : DefaultTask() {

  /**
   * Java source files. Stubs generated by the kotlin-kapt plugin.
   */
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:InputFiles
  abstract val kaptJavaStubs: ConfigurableFileCollection

  /**
   * Android layout XML files.
   */
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:InputFiles
  abstract val layoutFiles: ConfigurableFileCollection

  @get:OutputFile
  abstract val output: RegularFileProperty

  internal fun layouts(files: List<File>) {
    for (file in files) {
      layoutFiles.from(
        // TODO Gradle 6 can do objects.fileTree().from(file)
        objects.fileTree().from(file)//objects.fileCollection().from(file).asFileTree
          .matching {
            include { it.path.contains("layout") }
          }.files
      )
    }
  }
}

/**
 * Produces a report of all classes referenced by a given jar.
 */
@CacheableTask
abstract class JarAnalysisTask @Inject constructor(
  objects: ObjectFactory,
  private val workerExecutor: WorkerExecutor
) : ClassAnalysisTask(objects) {

  init {
    group = TASK_GROUP_DEP
    description = "Produces a report of all classes referenced by a given jar"
  }

  @get:Classpath
  abstract val jar: RegularFileProperty

  @TaskAction
  fun action() {
    // Output
    val reportFile = output.get().asFile
    reportFile.delete()

    val jarFile = jar.get().asFile
    logger.debug("jar path = ${jarFile.path}")

    workerExecutor.noIsolation().submit(JarAnalysisWorkAction::class.java) {
      jar = jarFile
      kaptJavaSource = kaptJavaStubs.files
      layouts = layoutFiles.files
      report = reportFile
    }
    workerExecutor.await()

    logger.debug("Report:\n${reportFile.readText()}")
  }
}

interface JarAnalysisParameters : WorkParameters {
  var jar: File
  var kaptJavaSource: Set<File>
  var layouts: Set<File>
  var report: File
}

abstract class JarAnalysisWorkAction : WorkAction<JarAnalysisParameters> {

  override fun execute() {
    val classNames = JarReader(
      jarFile = parameters.jar,
      layouts = parameters.layouts,
      kaptJavaSource = parameters.kaptJavaSource
    ).analyze()

    parameters.report.writeText(classNames.joinToString(separator = "\n"))
  }
}

/**
 * Produces a report of all classes referenced by a given set of class files.
 */
@CacheableTask
abstract class ClassListAnalysisTask @Inject constructor(
  objects: ObjectFactory,
  private val workerExecutor: WorkerExecutor
) : ClassAnalysisTask(objects) {

  init {
    group = TASK_GROUP_DEP
    description = "Produces a report of all classes referenced by a given set of class files"
  }

  /**
   * Class files generated by Kotlin source. May be empty.
   */
  @get:Classpath
  @get:InputFiles
  abstract val kotlinClasses: ConfigurableFileCollection

  /**
   * Class files generated by Java source. May be empty.
   */
  @get:Classpath
  @get:InputFiles
  abstract val javaClasses: ConfigurableFileCollection

  @TaskAction
  fun action() {
    // Output
    val reportFile = output.get().asFile
    reportFile.delete()

    val inputClassFiles = javaClasses.asFileTree.plus(kotlinClasses)
      .filter { it.isFile && it.name.endsWith(".class") }
      .files

    logger.debug("Java class files:${javaClasses.joinToString(prefix = "\n- ", separator = "\n- ") { it.path }}")
    logger.debug("Kotlin class files:${kotlinClasses.joinToString(prefix = "\n- ", separator = "\n- ") { it.path }}")

    workerExecutor.noIsolation().submit(ClassListAnalysisWorkAction::class.java) {
      classes = inputClassFiles
      kaptJavaSource = kaptJavaStubs.files
      layouts = layoutFiles.files
      report = reportFile
    }
    workerExecutor.await()

    logger.log("Class list usage report: ${reportFile.path}")
  }
}

interface ClassListAnalysisParameters : WorkParameters {
  var classes: Set<File>
  var kaptJavaSource: Set<File>
  var layouts: Set<File>
  var report: File
}

abstract class ClassListAnalysisWorkAction : WorkAction<ClassListAnalysisParameters> {

  override fun execute() {
    val classNames = ClassSetReader(
      classes = parameters.classes,
      layouts = parameters.layouts,
      kaptJavaSource = parameters.kaptJavaSource
    ).analyze()

    parameters.report.writeText(classNames.joinToString(separator = "\n"))
  }
}
