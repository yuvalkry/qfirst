package qfirst.frame.features

import qfirst.frame._

import qfirst.frame.util.Cell
import qfirst.frame.util.FileCached
import qfirst.frame.util.NonMergingMap
import qfirst.frame.util.VectorFileUtil

import java.nio.file._

import jjm.ling.ESpan
import jjm.ling.en.InflectedForms
import jjm.ling.en.VerbForm
import jjm.io.FileUtil
import jjm.implicits._

import cats.Order
import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._

import fs2.Stream

import io.circe.generic.JsonCodec
import io.circe.{Encoder, Decoder}

import freelog.EphemeralTreeLogger
import freelog.implicits._
import java.net.URL

abstract class PropBankFeatures[Arg](
  mode: RunMode,
  val assumeGoldVerbSense: Boolean)(
  implicit cs: ContextShift[IO],
  Log: EphemeralTreeLogger[IO, String]
) extends Features[String, Arg](mode)(implicitly[Encoder[String]], implicitly[Decoder[String]], cs, Log) {

  override def getIfPropBank: Option[PropBankFeatures[Arg]] = Some(this)

  override def getVerbLemma(verbType: String): String = {
    if(assumeGoldVerbSense) verbType.takeWhile(_ != '.')
    else verbType
  }

  // val inflectionDictionary
  import jjm.ling.en.Inflections

  val wiktionaryInflectionsURL = "https://www.dropbox.com/s/1wpsydqsuf9jm8v/en_verb_inflections.txt.gz?dl=1"
  def allInflectionLists(path: Path) = FileCached[List[InflectedForms]]("verb inflections")(
    path = path,
    read = path => FileUtil.readJsonLines[InflectedForms](path).compile.toList,
    write = (path, inflections) => FileUtil.writeJsonLines(path)(inflections))(
    Stream.resource(FileUtil.blockingExecutionContext).flatMap { ec =>
      val urlStream = IO(new URL(wiktionaryInflectionsURL).openConnection.getInputStream)
      fs2.io.readInputStream(urlStream, 4096, ec, true)
        .through(fs2.compress.gunzip(4096))
        .through(fs2.text.utf8Decode)
        .through(fs2.text.lines)
        .filter(_.trim.nonEmpty)
        .map { line =>
          val f = line.trim.split("\\t")
          InflectedForms.fromStrings(
            stem = f(0),
            presentSingular3rd = f(1),
            presentParticiple = f(2),
            past = f(3),
            pastParticiple = f(4)
          )
        }
    }.compile.toList
  )

  lazy val verbInflectedFormsByStem = new Cell(
    "Inflected forms by stem", {
      cacheDir.flatMap(dir => allInflectionLists(dir.resolve("en_verb_inflections.txt.gz")).get)
        .map(_.groupBy(_.stem))
    }
  )

  lazy val verbInflectedFormLists: IO[String => List[InflectedForms]] =
    verbInflectedFormsByStem.get.map(m => lemma => m.apply(lemma.lowerCase))


  def renderVerbType(verbType: String): String = verbType

  // don't store the models in the same dir, because they cluster different kinds of things
  override def modelDir = super.modelDir.map(
    _.resolve(if(assumeGoldVerbSense) "by-sense" else "by-lemma")
  ).flatTap(createDir)

  override def modelTuningDir = super.modelTuningDir.map(
    _.resolve(if(assumeGoldVerbSense) "by-sense" else "by-lemma")
  ).flatTap(createDir)

  def verbSenseLabels: CachedVerbFeats[String]

  def argRoleLabels: CachedArgFeats[PropBankRoleLabel]
}
