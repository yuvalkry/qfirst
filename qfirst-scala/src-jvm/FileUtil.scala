package qfirst
import qfirst.frames._
import FrameDataWriter.FrameInfo

import cats.implicits._
import cats.data.NonEmptyList
import cats.effect.{ContextShift, ExitCode, IO, IOApp, Resource}

import io.circe.jawn
import io.circe.{Encoder, Decoder}

import fs2.Stream

import java.nio.file.{Path => NIOPath}
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService

import io.circe.Printer

object FileUtil {

  val blockingExecutionContext =
    Resource.make(IO(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))))(ec => IO(ec.shutdown()))

  def streamJsonLines[A](
    path: NIOPath, ec: ExecutionContext)(
    implicit cs: ContextShift[IO], decoder: Decoder[A]
  ): Stream[IO, A] = fs2.io.file.readAll[IO](path, ec, 4096)
    .through(fs2.text.utf8Decode)
    .through(fs2.text.lines)
    .filter(_.nonEmpty)
    .flatMap(line => Stream.fromEither[IO](jawn.decode[A](line).left.map(e => new RuntimeException(s"${e.show}\n$line"))))

  def readJsonLines[A](
    path: NIOPath)(
    implicit cs: ContextShift[IO], decoder: Decoder[A]
  ): Stream[IO, A] = {
    Stream.resource(blockingExecutionContext).flatMap { _ec =>
      streamJsonLines(path, _ec)
    }
  }

  def readJson[A: Decoder](path: NIOPath): IO[A] = {
    IO(
      io.circe.jawn.decodeFile[A](new java.io.File(path.toString)) match {
        case Right(a) => a
        case Left(e) => throw new RuntimeException(s"${e.show}")
      }
    )
  }

  def writeJson[A: Encoder](path: NIOPath, printer: Printer)(a: A): IO[Unit] = {
    import io.circe.syntax._
    IO(Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))) >>
      IO(java.nio.file.Files.write(path, printer.pretty(a.asJson).getBytes("UTF-8")))
  }

  def writeJsonLines[A](path: NIOPath, printer: Printer)(as: Seq[A])(
    implicit cs: ContextShift[IO], encoder: Encoder[A]
  ): IO[Unit] = {
    import io.circe.syntax._
    IO(Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))) >>
      Stream.resource(blockingExecutionContext).flatMap { _ec =>
        Stream.emits[IO, A](as)
          .map(a => printer.pretty(a.asJson))
          .intersperse("\n")
          .through(fs2.text.utf8Encode)
          .through(fs2.io.file.writeAll(path, _ec))
      }.compile.drain
  }

  def readClauseInfo(
    path: NIOPath)(
    implicit cs: ContextShift[IO]
  ): IO[Map[String, Map[Int, Map[String, FrameInfo]]]] = {
    readJsonLines[FrameInfo](path)
      .map(fi => Map(fi.sentenceId -> Map(fi.verbIndex -> Map(fi.question -> List(fi)))))
      .compile.foldMonoid.map(
      // non-ideal.. would instead want a recursive combine that overrides and doesn't need the end mapping
      _.transform { case (sid, vs) => vs.transform { case (vi, qs) => qs.transform { case (q, fis) => fis.head } } }
    )
  }
}
