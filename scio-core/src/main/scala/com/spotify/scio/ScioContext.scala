/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// scalastyle:off file.size.limit

package com.spotify.scio

import java.beans.Introspector
import java.io.File
import java.net.URI
import java.nio.file.Files

import com.google.datastore.v1.{Entity, Query}
import com.spotify.scio.io.Tap
import com.spotify.scio.metrics.Metrics
import com.spotify.scio.io._
import com.spotify.scio.options.ScioOptions
import com.spotify.scio.testing._
import com.spotify.scio.util._
import com.spotify.scio.values._
import com.spotify.scio.coders.{Coder, CoderMaterializer}
import org.apache.beam.sdk.PipelineResult.State
import org.apache.beam.sdk.extensions.gcp.options.GcsOptions
import org.apache.beam.sdk.metrics.Counter
import org.apache.beam.sdk.options._
import org.apache.beam.sdk.transforms._
import org.apache.beam.sdk.values._
import org.apache.beam.sdk.{Pipeline, PipelineResult, io => beam}
import org.joda.time.Instant
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.{Buffer => MBuffer}
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Runner specific context. */
trait RunnerContext {
  def prepareOptions(options: PipelineOptions, artifacts: List[String]): Unit
}

private case object NoOpContext extends RunnerContext {
  override def prepareOptions(options: PipelineOptions, artifacts: List[String]): Unit = Unit
}

/** Direct runner specific context. */
private case object DirectContext extends RunnerContext {
  override def prepareOptions(options: PipelineOptions, artifacts: List[String]): Unit = Unit
}

/** Companion object for [[RunnerContext]]. */
private object RunnerContext {
  private val mapping =
    Map("DirectRunner" -> DirectContext.getClass.getName,
        "DataflowRunner" -> "com.spotify.scio.runners.dataflow.DataflowContext$")
      .withDefaultValue(NoOpContext.getClass.getName)

  // FIXME: this is ugly, is there a better way?
  private def get(options: PipelineOptions): RunnerContext = {
    val runner = options.getRunner.getSimpleName
    val cls = mapping(runner)
    try {
      Class
        .forName(cls)
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[RunnerContext]
    } catch {
      case e: Throwable =>
        throw new RuntimeException(s"Failed to load runner specific context $cls for $runner", e)
    }
  }

  def prepareOptions(options: PipelineOptions, artifacts: List[String]): Unit =
    get(options).prepareOptions(options, artifacts)
}

/** Convenience object for creating [[ScioContext]] and [[Args]]. */
object ContextAndArgs {
  // scalastyle:off regex
  // scalastyle:off cyclomatic.complexity
  /** Create [[ScioContext]] and [[Args]] for command line arguments. */
  def apply(args: Array[String]): (ScioContext, Args) = {
    val (_opts, _args) = ScioContext.parseArguments[PipelineOptions](args)
    (new ScioContext(_opts, Nil), _args)
  }

  import caseapp._
  import caseapp.core.help._
  def typed[T: Parser: Help](args: Array[String]): (ScioContext, T) = {
    // limit the options passed to case-app
    // to options supported in T
    val supportedCustomArgs =
      Parser[T].args
        .flatMap { a =>
          a.name +: a.extraNames
        }
        .map(_.name) ++ List("help", "usage")

    val Reg = "^-{1,2}(.+)$".r
    val (customArgs, remainingArgs) =
      args.partition {
        case Reg(a) =>
          val name = a.takeWhile(_ != '=')
          supportedCustomArgs.contains(name)
        case _ => true
      }

    CaseApp.detailedParseWithHelp[T](customArgs) match {
      case Left(message) =>
        Console.err.println(message.message)
        sys.exit(1)
      case Right((_, usage, help, _)) if help =>
        Console.out.println(Help[T].help)
        sys.exit(0)
      case Right((_, usage, help, _)) if usage =>
        SysProps.properties
          .map(_.show)
          .foreach(Console.out.println)

        Console.out.println(Help[T].help)
        for {
          i <- PipelineOptionsFactory.getRegisteredOptions.asScala
        } PipelineOptionsFactory.printHelp(Console.out, i)

        sys.exit(0)
      case Right((Right(t), usage, help, _)) =>
        val (ctx, _) = ContextAndArgs(remainingArgs)
        (ctx, t)
      case Right((Left(message), usage, help, _)) =>
        Console.err.println(message.message)
        sys.exit(1)
    }
  }
  // scalastyle:on regex
  // scalastyle:on cyclomatic.complexity
}

/** Companion object for [[ScioContext]]. */
object ScioContext {

  private val log = LoggerFactory.getLogger(this.getClass)

  import org.apache.beam.sdk.options.PipelineOptionsFactory

  /** Create a new [[ScioContext]] instance. */
  def apply(): ScioContext = ScioContext(defaultOptions)

  /** Create a new [[ScioContext]] instance. */
  def apply(options: PipelineOptions): ScioContext =
    new ScioContext(options, Nil)

  /** Create a new [[ScioContext]] instance. */
  def apply(artifacts: List[String]): ScioContext =
    new ScioContext(defaultOptions, artifacts)

  /** Create a new [[ScioContext]] instance. */
  def apply(options: PipelineOptions, artifacts: List[String]): ScioContext =
    new ScioContext(options, artifacts)

  /** Create a new [[ScioContext]] instance for testing. */
  def forTest(): ScioContext = {
    val opts = PipelineOptionsFactory
      .fromArgs("--appName=" + TestUtil.newTestId())
      .as(classOf[PipelineOptions])
    new ScioContext(opts, List[String]())
  }

  /** Parse PipelineOptions and application arguments from command line arguments. */
  @tailrec
  def parseArguments[T <: PipelineOptions: ClassTag](cmdlineArgs: Array[String],
                                                     withValidation: Boolean = false): (T, Args) = {
    val optClass = ScioUtil.classOf[T]

    // Extract --pattern of all registered derived types of PipelineOptions
    val classes = PipelineOptionsFactory.getRegisteredOptions.asScala + optClass
    val optPatterns = classes.flatMap { cls =>
      cls.getMethods
        .flatMap { m =>
          val n = m.getName
          if ((!n.startsWith("get") && !n.startsWith("is")) ||
              m.getParameterTypes.nonEmpty || m.getReturnType == classOf[Unit]) {
            None
          } else {
            Some(Introspector.decapitalize(n.substring(if (n.startsWith("is")) 2 else 3)))
          }
        }
        .map(s => s"--$s($$|=)".r)
    }

    // Split cmdlineArgs into 2 parts, optArgs for PipelineOptions and appArgs for Args
    val (optArgs, appArgs) =
      cmdlineArgs.partition(arg => optPatterns.exists(_.findFirstIn(arg).isDefined))

    val pipelineOpts = if (withValidation) {
      PipelineOptionsFactory.fromArgs(optArgs: _*).withValidation().as(optClass)
    } else {
      PipelineOptionsFactory.fromArgs(optArgs: _*).as(optClass)
    }

    val optionsFile = pipelineOpts.as(classOf[ScioOptions]).getOptionsFile
    if (optionsFile != null) {
      log.info(s"Appending options from $optionsFile")
      parseArguments(
        cmdlineArgs.filterNot(_.startsWith("--optionsFile=")) ++
          Source.fromFile(optionsFile).getLines())
    } else {
      val args = Args(appArgs)
      if (appArgs.nonEmpty) {
        pipelineOpts
          .as(classOf[ScioOptions])
          .setAppArguments(args.toString("", ", ", ""))
      }
      (pipelineOpts, args)
    }
  }

  import scala.language.implicitConversions

  /** Implicit conversion from ScioContext to DistCacheScioContext. */
  implicit def makeDistCacheScioContext(self: ScioContext): DistCacheScioContext =
    new DistCacheScioContext(self)

  private def defaultOptions: PipelineOptions = PipelineOptionsFactory.create()

}

/**
 * Main entry point for Scio functionality. A ScioContext represents a pipeline and can be used to
 * create SCollections and distributed caches on that cluster.
 *
 * @groupname dist_cache Distributed Cache
 * @groupname in_memory In-memory Collections
 * @groupname input Input Sources
 * @groupname Ungrouped Other Members
 */
// scalastyle:off number.of.methods
class ScioContext private[scio] (val options: PipelineOptions, private var artifacts: List[String])
    extends TransformNameable {

  private implicit val context: ScioContext = this

  private val logger = LoggerFactory.getLogger(this.getClass)

  import Implicits._

  /** Get PipelineOptions as a more specific sub-type. */
  def optionsAs[T <: PipelineOptions: ClassTag]: T =
    options.as(ScioUtil.classOf[T])

  // Set default name if no app name specified by user
  Try(optionsAs[ApplicationNameOptions]).foreach { o =>
    if (o.getAppName == null || o.getAppName.startsWith("ScioContext$")) {
      this.setAppName(CallSites.getAppName)
    }
  }

  // Set default job name if none specified by user
  if (options.getJobName == null) {
    options.setJobName(optionsAs[ApplicationNameOptions].getAppName) // appName already set
  }

  {
    VersionUtil.checkVersion()
    VersionUtil.checkRunnerVersion(options.getRunner)
    val o = optionsAs[ScioOptions]
    o.setScalaVersion(BuildInfo.scalaVersion)
    o.setScioVersion(BuildInfo.version)
  }

  {
    // Check if running within scala.App. See https://github.com/spotify/scio/issues/449
    if (Thread
          .currentThread()
          .getStackTrace
          .toList
          .map(_.getClassName.split('$').head)
          .exists(_.equals(classOf[App].getName))) {
      logger.warn(
        "Applications defined within scala.App might not work properly. Please use main method!")
    }
  }

  private[scio] val testId: Option[String] =
    Try(optionsAs[ApplicationNameOptions]).toOption.flatMap { o =>
      if (TestUtil.isTestId(o.getAppName)) {
        Some(o.getAppName)
      } else {
        None
      }
    }

  /** Amount of time to block job for. */
  private[scio] val awaitDuration: Duration = {
    val blockFor = optionsAs[ScioOptions].getBlockFor
    try {
      Option(blockFor)
        .map(Duration(_))
        .getOrElse(Duration.Inf)
    } catch {
      case e: NumberFormatException =>
        throw new IllegalArgumentException(
          s"blockFor param $blockFor cannot be cast to " +
            s"type scala.concurrent.duration.Duration")
    }
  }

  // if in local runner, temp location may be needed, but is not currently required by
  // the runner, which may end up with NPE. If not set but user generate new temp dir
  if (ScioUtil.isLocalRunner(options.getRunner) && options.getTempLocation == null) {
    val tmpDir = Files.createTempDirectory("scio-temp-")
    logger.debug(s"New temp directory at $tmpDir")
    options.setTempLocation(tmpDir.toString)
  }

  /** Underlying pipeline. */
  def pipeline: Pipeline = {
    if (_pipeline == null) {
      // TODO: make sure this works for other PipelineOptions
      RunnerContext.prepareOptions(options, artifacts)
      _pipeline = if (testId.isEmpty) {
        Pipeline.create(options)
      } else {
        TestDataManager.startTest(testId.get)
        // load TestPipeline dynamically to avoid ClassNotFoundException when running src/main
        // https://issues.apache.org/jira/browse/BEAM-298
        val cls = Class.forName("org.apache.beam.sdk.testing.TestPipeline")
        // propagate options
        val opts = PipelineOptionsFactory.create()
        opts.setStableUniqueNames(options.getStableUniqueNames)
        val tp = cls
          .getMethod("fromOptions", classOf[PipelineOptions])
          .invoke(null, opts)
          .asInstanceOf[Pipeline]
        // workaround for @Rule enforcement introduced by
        // https://issues.apache.org/jira/browse/BEAM-1205
        cls
          .getMethod("enableAbandonedNodeEnforcement", classOf[Boolean])
          .invoke(tp, Boolean.box(true))
        tp
      }
      _pipeline.getCoderRegistry.registerScalaCoders()
    }
    _pipeline
  }

  /* Mutable members */
  private var _pipeline: Pipeline = _
  private var _isClosed: Boolean = false
  private val _promises: MBuffer[(Promise[Tap[_]], Tap[_])] = MBuffer.empty
  private val _preRunFns: MBuffer[() => Unit] = MBuffer.empty
  private val _counters: MBuffer[Counter] = MBuffer.empty
  private var _onClose: Unit => Unit = identity
  private val _localInstancesCache: scala.collection.mutable.Map[ClassTag[_], Any] =
    scala.collection.mutable.Map.empty

  /** Wrap a [[org.apache.beam.sdk.values.PCollection PCollection]]. */
  def wrap[T](p: PCollection[T]): SCollection[T] =
    new SCollectionImpl[T](p, this)

  /**
   * Add callbacks calls when the context is closed.
   */
  private[scio] def onClose(f: Unit => Unit): Unit =
    _onClose = _onClose compose f

  /*
   * Get from or put in an object in this context local cache
   * This method is used in `scio-bigquery` to only instantiate the BigQuery client once
   * even if there's multiple implicit conversions from [[ScioContext]] to `BigQueryScioContext`
   */
  private[scio] def cached[T: ClassTag](t: => T): T = {
    val key = implicitly[ClassTag[T]]
    _localInstancesCache
      .getOrElse(key, {
        _localInstancesCache += key -> t
        t
      })
      .asInstanceOf[T]
  }

  // =======================================================================
  // States
  // =======================================================================

  /** Set application name for the context. */
  def setAppName(name: String): Unit = {
    if (_pipeline != null) {
      throw new RuntimeException("Cannot set application name once pipeline is initialized")
    }
    Try(optionsAs[ApplicationNameOptions]).foreach(_.setAppName(name))
  }

  /** Set job name for the context. */
  def setJobName(name: String): Unit = {
    if (_pipeline != null) {
      throw new RuntimeException("Cannot set job name once pipeline is initialized")
    }
    options.setJobName(name)
  }

  /** Close the context. No operation can be performed once the context is closed. */
  def close(): ScioResult = requireNotClosed {
    _onClose(())

    if (_counters.nonEmpty) {
      val counters = _counters.toArray
      this.parallelize(Seq(0)).map { _ =>
        counters.foreach(_.inc(0))
      }
    }

    _isClosed = true

    _preRunFns.foreach(_())
    val result = new ContextScioResult(this.pipeline.run(), context)

    if (this.isTest) {
      TestDataManager.closeTest(testId.get, result)
    }

    if (this.isTest || (this
          .optionsAs[ScioOptions]
          .isBlocking && awaitDuration == Duration.Inf)) {
      result.waitUntilDone()
    } else {
      result
    }
  }

  private class ContextScioResult(internal: PipelineResult, val context: ScioContext)
      extends ScioResult(internal) {
    override val finalState: Future[State] = {
      import scala.concurrent.ExecutionContext.Implicits.global
      val f = Future {
        val state = internal.waitUntilFinish()
        context.updateFutures(state)
        val metricsLocation = context.optionsAs[ScioOptions].getMetricsLocation
        if (metricsLocation != null) {
          saveMetrics(metricsLocation)
        }
        this.state
      }
      f.onComplete {
        case Success(_)           => Unit
        case Failure(NonFatal(_)) => context.updateFutures(state)
      }
      f
    }

    override def getMetrics: Metrics =
      Metrics(BuildInfo.version,
              BuildInfo.scalaVersion,
              context.optionsAs[ApplicationNameOptions].getAppName,
              state.toString,
              getBeamMetrics)

    override def getAwaitDuration: Duration = awaitDuration

    override def isTest: Boolean = context.isTest
  }

  /** Whether the context is closed. */
  def isClosed: Boolean = _isClosed

  /** Ensure an operation is called before the pipeline is closed. */
  private[scio] def requireNotClosed[T](body: => T): T = {
    require(!this.isClosed, "ScioContext already closed")
    body
  }

  // =======================================================================
  // Futures
  // =======================================================================

  // To be updated once the pipeline completes.
  private[scio] def makeFuture[T](value: Tap[T]): Future[Tap[T]] = {
    val p = Promise[Tap[T]]()
    _promises.append((p.asInstanceOf[Promise[Tap[_]]], value.asInstanceOf[Tap[_]]))
    p.future
  }

  // Update pending futures after pipeline completes.
  private[scio] def updateFutures(state: State): Unit = _promises.foreach { kv =>
    if (state == State.DONE || state == State.UPDATED) {
      kv._1.success(kv._2)
    } else {
      kv._1.failure(new RuntimeException("Pipeline failed to complete: " + state))
    }
  }

  // =======================================================================
  // Test wiring
  // =======================================================================

  /**  Whether this is a test context. */
  def isTest: Boolean = testId.isDefined

  private[scio] def testInput: TestInput = TestDataManager.getInput(testId.get)
  private[scio] def testOutput: TestOutput =
    TestDataManager.getOutput(testId.get)
  private[scio] def testDistCache: TestDistCache =
    TestDataManager.getDistCache(testId.get)

  private[scio] def testOut[T](io: ScioIO[T]): SCollection[T] => Unit =
    testOutput(io)

  private[scio] def getTestInput[T: Coder](io: ScioIO[T]): SCollection[T] =
    this.parallelize(testInput(io).asInstanceOf[Seq[T]])

  // =======================================================================
  // Read operations
  // =======================================================================

  private[scio] def applyInternal[Output <: POutput](
    root: PTransform[_ >: PBegin, Output]): Output =
    pipeline.apply(this.tfName, root)

  /**
   * Get an SCollection for a Datastore query.
   * @group input
   */
  def datastore(projectId: String, query: Query, namespace: String = null): SCollection[Entity] =
    this.read(DatastoreIO(projectId))(DatastoreIO.ReadParam(query, namespace))

  private def pubsubIn[T: ClassTag: Coder](isSubscription: Boolean,
                                           name: String,
                                           idAttribute: String,
                                           timestampAttribute: String): SCollection[T] = {
    val io = PubsubIO[T](name, idAttribute, timestampAttribute)
    this.read(io)(PubsubIO.ReadParam(isSubscription))
  }

  /**
   * Get an SCollection for a Pub/Sub subscription.
   * @group input
   */
  def pubsubSubscription[T: ClassTag: Coder](sub: String,
                                             idAttribute: String = null,
                                             timestampAttribute: String = null): SCollection[T] =
    pubsubIn(isSubscription = true, sub, idAttribute, timestampAttribute)

  /**
   * Get an SCollection for a Pub/Sub topic.
   * @group input
   */
  def pubsubTopic[T: ClassTag: Coder](topic: String,
                                      idAttribute: String = null,
                                      timestampAttribute: String = null): SCollection[T] =
    pubsubIn(isSubscription = false, topic, idAttribute, timestampAttribute)

  private def pubsubInWithAttributes[T: ClassTag: Coder](
    isSubscription: Boolean,
    name: String,
    idAttribute: String,
    timestampAttribute: String): SCollection[(T, Map[String, String])] = {
    val io = PubsubIO.withAttributes[T](name, idAttribute, timestampAttribute)
    this.read(io)(PubsubIO.ReadParam(isSubscription))
  }

  /**
   * Get an SCollection for a Pub/Sub subscription that includes message attributes.
   * @group input
   */
  def pubsubSubscriptionWithAttributes[T: ClassTag: Coder](
    sub: String,
    idAttribute: String = null,
    timestampAttribute: String = null): SCollection[(T, Map[String, String])] =
    pubsubInWithAttributes[T](isSubscription = true, sub, idAttribute, timestampAttribute)

  /**
   * Get an SCollection for a Pub/Sub topic that includes message attributes.
   * @group input
   */
  def pubsubTopicWithAttributes[T: ClassTag: Coder](
    topic: String,
    idAttribute: String = null,
    timestampAttribute: String = null): SCollection[(T, Map[String, String])] =
    pubsubInWithAttributes[T](isSubscription = false, topic, idAttribute, timestampAttribute)

  /**
   * Get an SCollection for a text file.
   * @group input
   */
  def textFile(path: String,
               compression: beam.Compression = beam.Compression.AUTO): SCollection[String] =
    this.read(TextIO(path))(TextIO.ReadParam(compression))

  /**
   * Get an SCollection with a custom input transform. The transform should have a unique name.
   * @group input
   */
  def customInput[T: Coder, I >: PBegin <: PInput](
    name: String,
    transform: PTransform[I, PCollection[T]]): SCollection[T] =
    requireNotClosed {
      if (this.isTest) {
        this.getTestInput(CustomIO[T](name))
      } else {
        wrap(this.pipeline.apply(name, transform))
      }
    }

  /**
   * Generic read method for all `ScioIO[T]` implementations, if it is test pipeline this will
   * feed value of pre-registered input IO implementation which match for the passing `ScioIO[T]`
   * implementation. if not this will invoke [[com.spotify.scio.io.ScioIO[T]#read]] method along
   * with read configurations passed by.
   *
   * @param io     an implementation of `ScioIO[T]` trait
   * @param params configurations need to pass to perform underline read implementation
   */
  def read[T: Coder](io: ScioIO[T])(params: io.ReadP): SCollection[T] =
    readImpl[T](io)(params)

  private def readImpl[T: Coder](io: ScioIO[T])(params: io.ReadP): SCollection[T] =
    requireNotClosed {
      if (this.isTest) {
        this.getTestInput(io)
      } else {
        io.read(this, params)
      }
    }

  // scalastyle:off structural.type
  def read[T: Coder](io: ScioIO[T] { type ReadP = Unit }): SCollection[T] =
    readImpl[T](io)(())
  // scalastyle:on structural.type

  private[scio] def addPreRunFn(f: () => Unit): Unit = _preRunFns += f

  // =======================================================================
  // In-memory collections
  // =======================================================================

  /** Create a union of multiple SCollections. Supports empty lists. */
  def unionAll[T: Coder](scs: Iterable[SCollection[T]]): SCollection[T] =
    scs match {
      case Nil      => empty()
      case contents => SCollection.unionAll(contents)
    }

  /** Form an empty SCollection. */
  def empty[T: Coder](): SCollection[T] = parallelize(Seq())

  /**
   * Distribute a local Scala `Iterable` to form an SCollection.
   * @group in_memory
   */
  def parallelize[T: Coder](elems: Iterable[T]): SCollection[T] =
    requireNotClosed {
      wrap(
        this.applyInternal(
          Create
            .of(elems.asJava)
            .withCoder(CoderMaterializer.beam(context, Coder[T]))))
    }

  /**
   * Distribute a local Scala `Map` to form an SCollection.
   * @group in_memory
   */
  def parallelize[K, V](elems: Map[K, V])(implicit koder: Coder[K],
                                          voder: Coder[V]): SCollection[(K, V)] =
    requireNotClosed {
      val kvc = CoderMaterializer.kvCoder[K, V](context)
      wrap(this.applyInternal(Create.of(elems.asJava).withCoder(kvc)))
        .map(kv => (kv.getKey, kv.getValue))
    }

  /**
   * Distribute a local Scala `Iterable` with timestamps to form an SCollection.
   * @group in_memory
   */
  def parallelizeTimestamped[T: ClassTag](elems: Iterable[(T, Instant)]): SCollection[T] =
    requireNotClosed {
      val coder = pipeline.getCoderRegistry.getScalaCoder[T](options)
      val v = elems.map(t => TimestampedValue.of(t._1, t._2))
      wrap(this.applyInternal(Create.timestamped(v.asJava).withCoder(coder)))
    }

  /**
   * Distribute a local Scala `Iterable` with timestamps to form an SCollection.
   * @group in_memory
   */
  def parallelizeTimestamped[T: ClassTag](elems: Iterable[T],
                                          timestamps: Iterable[Instant]): SCollection[T] =
    requireNotClosed {
      val coder = pipeline.getCoderRegistry.getScalaCoder[T](options)
      val v = elems.zip(timestamps).map(t => TimestampedValue.of(t._1, t._2))
      wrap(this.applyInternal(Create.timestamped(v.asJava).withCoder(coder)))
    }

  // =======================================================================
  // Metrics
  // =======================================================================

  /**
   * Initialize a new [[org.apache.beam.sdk.metrics.Counter Counter]] metric using `T` as namespace.
   * Default is "com.spotify.scio.ScioMetrics" if `T` is not specified.
   */
  def initCounter[T: ClassTag](name: String): Counter = {
    val counter = ScioMetrics.counter[T](name)
    _counters.append(counter)
    counter
  }

  /** Initialize a new [[org.apache.beam.sdk.metrics.Counter Counter]] metric. */
  def initCounter(namespace: String, name: String): Counter = {
    val counter = ScioMetrics.counter(namespace, name)
    _counters.append(counter)
    counter
  }

}
// scalastyle:on number.of.methods

/** An enhanced ScioContext with distributed cache features. */
class DistCacheScioContext private[scio] (self: ScioContext) {

  private[scio] def testDistCache: TestDistCache =
    TestDataManager.getDistCache(self.testId.get)

  /**
   * Create a new [[com.spotify.scio.values.DistCache DistCache]] instance.
   * @param uri Google Cloud Storage URI of the file to be distributed to all workers
   * @param initFn function to initialized the distributed file
   *
   * {{{
   * // Prepare distributed cache as Map[Int, String]
   * val dc = sc.distCache("gs://dataflow-samples/samples/misc/months.txt") { f =>
   *   scala.io.Source.fromFile(f).getLines().map { s =>
   *     val t = s.split(" ")
   *     (t(0).toInt, t(1))
   *   }.toMap
   * }
   *
   * val p: SCollection[Int] = // ...
   * // Extract distributed cache inside a transform
   * p.map(x => dc().getOrElse(x, "unknown"))
   * }}}
   * @group dist_cache
   */
  def distCache[F](uri: String)(initFn: File => F): DistCache[F] =
    self.requireNotClosed {
      if (self.isTest) {
        new MockDistCacheFunc(testDistCache(DistCacheIO(uri)))
      } else {
        new DistCacheSingle(new URI(uri), initFn, self.optionsAs[GcsOptions])
      }
    }

  /**
   * Create a new [[com.spotify.scio.values.DistCache DistCache]] instance.
   * @param uris Google Cloud Storage URIs of the files to be distributed to all workers
   * @param initFn function to initialized the distributed files
   * @group dist_cache
   */
  def distCache[F](uris: Seq[String])(initFn: Seq[File] => F): DistCache[F] =
    self.requireNotClosed {
      if (self.isTest) {
        new MockDistCacheFunc(testDistCache(DistCacheIO(uris)))
      } else {
        new DistCacheMulti(uris.map(new URI(_)), initFn, self.optionsAs[GcsOptions])
      }
    }

}

// scalastyle:on file.size.limit
