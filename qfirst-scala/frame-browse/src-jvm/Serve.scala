package qfirst.frame.browse
import qfirst._
import qfirst.frame._
import qfirst.frame.features._

import qasrl.bank.Data
import qasrl.data.Dataset
import qasrl.labeling.SlotBasedLabel

import cats.~>
import cats.Id
import cats.Order
import cats.data.NonEmptySet
import cats.data.Validated
import cats.effect.IO
import cats.effect.{IOApp, ExitCode}
import cats.effect.concurrent.Ref
import cats.implicits._

import fs2.Stream

import io.circe.{Encoder, Decoder}

import qasrl.bank.service.DocumentService
import qasrl.bank.service.Search

import java.nio.file.Path
import java.nio.file.Files

import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits._

import jjm.io.FileUtil
import jjm.io.HttpUtil
import jjm.ling.en.InflectedForms
import jjm.ling.en.VerbForm

import freelog.EphemeralTreeLogger

object Serve extends CommandIOApp(
  name = "mill -i qfirst.jvm.runVerbAnn",
  header = "Spin up the annotation server for QA-SRL Clause frames.") {

  import scala.concurrent.ExecutionContext.Implicits.global

  val docApiSuffix = "doc"
  val verbApiSuffix = "verb"
  val featureApiSuffix = "feature"

  def readCompleteClusterModels[VerbType: Encoder : Decoder, Arg: Encoder : Decoder : Order](
    model: ClusteringModel, features: Features[VerbType, Arg])(
    implicit Log: EphemeralTreeLogger[IO, String]
  ): IO[Map[VerbType, VerbClusterModel[VerbType, Arg]]] = model match {
    case m: JointModel => FrameInductionApp.getVerbFrames(m, features).flatMap(_.read.map(_.get))
    case m: VerbModel => FrameInductionApp.getVerbClusters(m, features).flatMap(_.read.map(_.get))
        .map(clusterings =>
          clusterings.map { case (verbType, verbTree) =>
            verbType -> VerbClusterModel[VerbType, Arg](verbType, verbTree, Clustering(None, Map()))
          }
        )
    case m: ArgumentModel => FrameInductionApp.getArgumentClusters(m, features).flatMap(_.read.map(_.get))
        .map(clusterings =>
          clusterings.map { case (verbType, argClustering) =>
            val allArgIds = argClustering.clusterTreeOpt.foldMap(_.unorderedFold) ++ argClustering.extraClusters.unorderedFold
            val allVerbIds = allArgIds.map(_.verbId)
            val verbTree = MergeTree.Leaf(0.0, allVerbIds)
            val verbClustering = Clustering(Some(verbTree))
            verbType -> VerbClusterModel[VerbType, Arg](verbType, verbClustering, argClustering)
          }
        )
  }

  def _runSpecified[VerbType: Encoder : Decoder, Arg: Encoder : Decoder : Order](
    features: Features[VerbType, Arg],
    pageService: org.http4s.HttpRoutes[IO],
    model: ClusteringModel,
    port: Int)(
    implicit Log: EphemeralTreeLogger[IO, String]
  ): IO[ExitCode] = {
    val featureService = HttpUtil.makeHttpPostServer(FeatureService.baseService(features))

    for {
      verbModels <- readCompleteClusterModels[VerbType, Arg](model, features)
      verbModelService = HttpUtil.makeHttpPostServer(VerbFrameService.basicIOService(verbModels))
      app = Router(
        "/" -> pageService,
        s"/$verbApiSuffix" -> verbModelService,
        s"/$featureApiSuffix" -> featureService
      ).orNotFound
      _ <- Log.info("Starting server.")
      _ <- BlazeServerBuilder[IO]
      .bindHttp(port, "0.0.0.0")
      .withHttpApp(app)
      .serve.compile.drain
    } yield ExitCode.Success
  }

  def _run(
    jsDepsPath: Path, jsPath: Path,
    dataSetting: DataSetting,
    mode: RunMode,
    model: ClusteringModel,
    domain: String,
    port: Int
  ): IO[ExitCode] = {
    freelog.loggers.TimingEphemeralTreeFansiLogger.create().flatMap { implicit Log =>
      val pageService = StaticPageService.makeService(
        domain,
        docApiSuffix, verbApiSuffix, featureApiSuffix,
        dataSetting, mode,
        jsDepsPath, jsPath, port
      )

      dataSetting match {
        case d @ DataSetting.Qasrl         => _runSpecified(FrameInductionApp.getFeatures(d, mode), pageService, model, port)
        case d @ DataSetting.Ontonotes5(_) => _runSpecified(FrameInductionApp.getFeatures(d, mode), pageService, model, port)
        case d @ DataSetting.CoNLL08(_)    => _runSpecified(FrameInductionApp.getFeatures(d, mode), pageService, model, port)
      }
    }
  }

  def main: Opts[IO[ExitCode]] = {

    val jsDepsPathO = Opts.option[Path](
      "jsDeps", metavar = "path", help = "Where to get the JS deps file."
    )

    val jsPathO = Opts.option[Path](
      "js", metavar = "path", help = "Where to get the JS main file."
    )

    val dataO = FrameInductionApp.dataO

    val modeO = FrameInductionApp.modeO

    val modelO = FrameInductionApp.modelO

    val domainO = Opts.option[String](
      "domain", metavar = "domain", help = "domain name the server is being hosted at."
    )

    val portO = Opts.option[Int](
      "port", metavar = "port number", help = "Port to host the HTTP service on."
    )

    // val domainRestrictionO = Opts.option[String](
    //   "domain", metavar = "http://...",
    //   help = "Domain to impose CORS restrictions to (otherwise, all domains allowed)."
    // ).map(NonEmptySet.of(_)).orNone

    (jsDepsPathO, jsPathO, dataO, modeO, modelO, domainO, portO).mapN(_run)
  }
}
