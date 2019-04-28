package qfirst.paraphrase.browse
import qfirst.paraphrase._

import scala.concurrent.Future

import nlpdata.datasets.wiktionary.InflectedForms

import io.circe.parser.decode
import io.circe.syntax._

case class VerbFrameClient(apiUrl: String) extends VerbFrameService[Future] {

  import qasrl.data.JsonCodecs.{inflectedFormsEncoder, inflectedFormsDecoder}
  import scala.concurrent.ExecutionContext.Implicits.global
  val printer = io.circe.Printer.noSpaces

  def getVerbs: Future[Map[InflectedForms, Int]] = {
    import io.circe.generic.auto._
    org.scalajs.dom.ext.Ajax.post(url = apiUrl + "/getVerbs", data = printer.pretty(().asJson)).map(_.responseText).flatMap { jsonStr =>
      decode[List[(InflectedForms, Int)]](jsonStr) match {
        case Left(err)  => Future.failed[Map[InflectedForms, Int]](new RuntimeException(err))
        case Right(res) => Future.successful(res.toMap)
      }
    }
  }

  def getFrameset(inflectedForms: InflectedForms): Future[VerbFrameset] = {
    org.scalajs.dom.ext.Ajax.post(url = apiUrl + "/getFrameset", data = printer.pretty(inflectedForms.asJson)).map(_.responseText).flatMap { jsonStr =>
      decode[VerbFrameset](jsonStr) match {
        case Left(err)  => Future.failed[VerbFrameset](new RuntimeException(err))
        case Right(res) => Future.successful(res)
      }
    }
  }

  def getParaphrasingInfo(i: Int): Future[ParaphrasingInfo] = {
    org.scalajs.dom.ext.Ajax.post(url = apiUrl + "/getParaphrasingInfo", data = printer.pretty(i.asJson)).map(_.responseText).flatMap { jsonStr =>
      decode[ParaphrasingInfo](jsonStr) match {
        case Left(err)  => Future.failed[ParaphrasingInfo](new RuntimeException(err))
        case Right(res) => Future.successful(res)
      }
    }
  }

  def saveParaphraseAnnotations(sentenceId: String, verbIndex: Int, paraphrases: VerbParaphraseLabels): Future[VerbParaphraseLabels] = {
    org.scalajs.dom.ext.Ajax.post(url = apiUrl + "/saveParaphraseAnnotations", data = printer.pretty((sentenceId, verbIndex, paraphrases).asJson)).map(_.responseText).flatMap { jsonStr =>
      decode[VerbParaphraseLabels](jsonStr) match {
        case Left(err)  => Future.failed[VerbParaphraseLabels](new RuntimeException(err))
        case Right(res) => Future.successful(res)
      }
    }
  }

}