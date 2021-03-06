package qfirst.frame.ann

import jjm.io.FileUtil
import jjm.io.HttpUtil

import qasrl.bank.Data

import cats.~>
import cats.Id
import cats.data.NonEmptySet
import cats.effect.IO
import cats.effect.{IOApp, ExitCode}
import cats.effect.concurrent.Ref
import cats.implicits._

import fs2.Stream

import qasrl.bank.service.DocumentService
import qasrl.bank.service.Search

import java.nio.file.Path
import java.nio.file.Files

import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits._

object Serve extends CommandIOApp(
  name = "mill -i qfirst.jvm.runVerbAnn",
  header = "Spin up the annotation server for QA-SRL Clause frames.") {

  import scala.concurrent.ExecutionContext.Implicits.global

  def program(
    jsDepsPath: Path, jsPath: Path,
    qasrlBankPath: Path, savePath: Path,
    domain: String, port: Int
  ): IO[ExitCode] = {
    IO(Data.readFromQasrlBank(qasrlBankPath).toEither.right.get).flatMap { data =>
      val docApiSuffix = "doc"
      val verbApiSuffix = "verb"
      val pageService = StaticPageService.makeService(
        domain,
        docApiSuffix, verbApiSuffix,
        jsDepsPath, jsPath, port
      )

      val index = data.index
      val docs = data.documentsById
      val searchIndex = Search.createSearchIndex(docs.values.toList)
      val docService = HttpUtil.makeHttpPostServer(
        DocumentService.basic(index, docs, searchIndex)
          .andThenK(Lambda[Id ~> IO](IO.pure(_)))
      )

      val saveData = (data: VerbFrameData) => {
        FileUtil.writeJson(savePath, io.circe.Printer.noSpaces)(data)
      }

      for {
        initData <- {
          if(Files.exists(savePath)) FileUtil.readJson[VerbFrameData](savePath)
          else IO.pure(VerbFrameData.newFromDataset(data.trainExpanded))
        }
        initDataRef <- Ref[IO].of(initData)
        annotationService = HttpUtil.makeHttpPostServer(
          VerbAnnotationService.basicIOService(initDataRef, saveData)
        )
        app = Router(
          "/" -> pageService,
          s"/$docApiSuffix" -> docService,
          s"/$verbApiSuffix" -> annotationService,
        ).orNotFound
        _ <- BlazeServerBuilder[IO]
        .bindHttp(port, "0.0.0.0")
        .withHttpApp(app)
        .serve.compile.drain
      } yield ExitCode.Success
    }
  }

  def main: Opts[IO[ExitCode]] = {

    val jsDepsPathO = Opts.option[Path](
      "jsDeps", metavar = "path", help = "Where to get the JS deps file."
    )

    val jsPathO = Opts.option[Path](
      "js", metavar = "path", help = "Where to get the JS main file."
    )

    val qasrlBankO = Opts.option[Path](
      "qasrl-bank", metavar = "path", help = "Path to the QA-SRL Bank 2.0 data, e.g., ../qasrl-bank/data/qasrl-v2."
    )

    val saveO = Opts.option[Path](
      "save", metavar = "path", help = "Where to save the clause resolution json object."
    )

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
    //
    (jsDepsPathO, jsPathO, qasrlBankO, saveO, domainO, portO).mapN(program(_, _, _, _, _, _))
  }
}
