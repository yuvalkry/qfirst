package qfirst

import qfirst.metrics._

import cats.implicits._
import cats.data.NonEmptyList
import cats.effect.IO

import com.monovore.decline._

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

import nlpdata.util.Text
import nlpdata.util.LowerCaseStrings._

import qasrl.bank.Data
import qasrl.bank.SentenceId

import qasrl.data.{AnswerJudgment, Answer, InvalidQuestion}
import qasrl.data.AnswerSpan
import qasrl.data.Dataset
import qasrl.data.QuestionLabel
import qasrl.data.Sentence
import qasrl.data.VerbEntry

import HasMetrics.ops._

object SandboxApp extends App {
  val train = Data.readDataset(Paths.get("qasrl-v2_1").resolve("orig").resolve("train.jsonl.gz"))
  val dev = Data.readDataset(Paths.get("qasrl-v2_1").resolve("orig").resolve("dev.jsonl.gz"))

  val sortSpec = {
    import Metric._
    import MapTree.SortQuery._
    val double = (mv: Metric) => mv match {
      case MetricMetadata(s) => 0.0
      case MetricBool(x) => if(x) 1.0 else 0.0
      case MetricInt(x) => x.toDouble
      case MetricDouble(x) => x
      case MetricIntOfTotal(x, _) => x.toDouble
    }
    val inc = value[String](double)
    val dec = value[String](double andThen (_ * -1))
    List(
      "predictions" :: "f1" :: inc,
      "full question" :: "f1" :: inc,
      "full question" :: "acc-lb" :: inc,
      "num predicted" :: inc,
      "mean" :: inc
    )
  }

  def getMetricsString[M: HasMetrics](m: M) =
    m.getMetrics.toStringPrettySorted(identity, x => x.render, sortSpec)

  import qfirst.{Instances => I}
  import qfirst.metrics.{Transformers => M}
  import shapeless._
  import shapeless.syntax.singleton._
  import shapeless.record._
  import monocle.function.{all => Optics}

  import qfirst.frames.TemplateStateMachine.allPrepositions

  def getPrepMetrics(sentence: Sentence, verb: VerbEntry) = (qLabel: QuestionLabel) => {
    val hasPrep = qLabel.answerJudgments
      .flatMap(_.judgment.getAnswer)
      .flatMap(_.spans)
      .map(s =>
      (
        if(allPrepositions.contains(sentence.sentenceTokens(s.begin).lowerCase)) 3
        else if(sentence.sentenceTokens.lift(s.begin - 1).map(_.lowerCase).exists(allPrepositions.contains)) 2
        else 1
      )
    ).max match {
      case 1 => "N"
      case 2 => "-"
      case 3 => "Y"
    }
    Bucketed(
      Map(
        Map("wh" -> qLabel.questionSlots.wh.toString) ->
          Bucketed(
            Map(
              Map("has prep" -> hasPrep) -> 1
            )
          )
      )
    )
  }

  lazy val prepMetrics = train.sentences.values.toList.foldMap { sentence =>
    sentence.verbEntries.values.toList.foldMap { verb =>
      verb.questionLabels.values.toList.foldMap {
        getPrepMetrics(sentence, verb)
      }
    }
  }

  val getSpanLengthMetrics = (aj: AnswerJudgment) => aj match {
    case InvalidQuestion => Counts(0)
    case Answer(spans) => spans.toList.foldMap(s => Counts(s.end - s.begin))
  }

  val spanLengthMetrics = train.sentences.values.toList.foldMap { sentence =>
    sentence.verbEntries.values.toList.foldMap { verb =>
      verb.questionLabels.values.toList.foldMap { qLabel =>
        qLabel.answerJudgments.toList.map(_.judgment).foldMap(getSpanLengthMetrics)
      }
    }
  }

  val getSpanCounts = (sentence: Sentence) => {
    sentence.verbEntries.values.toList.foldMap { verb =>
      verb.questionLabels.values.toList.foldMap { qLabel =>
        qLabel.answerJudgments.toList.map(_.sourceId).foldMap(s => Map(s -> 1))
      }.values.toList.foldMap(Counts(_))
    }
  }

  val getSentenceLength = (sentence: Sentence) => {
    Counts(sentence.sentenceTokens.size)
  }

  val getTanCounts = (verb: VerbEntry) => {
    "TANs" ->> Counts(verb.questionLabels.values.toList.map(l =>
      (l.tense, l.isPerfect, l.isProgressive, l.isNegated)
    ).toSet.size) ::
      "TANs Without Negation" ->> Counts(verb.questionLabels.values.toList.map(l =>
        (l.tense, l.isPerfect, l.isProgressive)
      ).toSet.size) :: HNil
  }

  val trainTanMetrics = train.sentences.values.toList.flatMap(_.verbEntries.values.toList).foldMap(getTanCounts)
  val devTanMetrics = dev.sentences.values.toList.flatMap(_.verbEntries.values.toList).foldMap(getTanCounts)
  println("Train TAN counts: " + getMetricsString(trainTanMetrics))
  println("Train TAN hist:\n" + trainTanMetrics("TANs").histogramString(75))
  println("Train TAN hist (no negation):\n" + trainTanMetrics("TANs Without Negation").histogramString(75))
  println("Dev tan counts: " + getMetricsString(devTanMetrics))
  println("Dev TAN hist:\n" + devTanMetrics("TANs").histogramString(75))
  println("Dev TAN hist (no negation):\n" + devTanMetrics("TANs Without Negation").histogramString(75))

  // println(getMetricsString(spanLengthMetrics))
  // println(spanLengthMetrics.histogramString(75))
  // println("Train span counts: " + getMetricsString(train.sentences.values.toList.foldMap(getSpanCounts)))
  // println("Dev span counts: " + getMetricsString(dev.sentences.values.toList.foldMap(getSpanCounts)))

  // val trainMetrics = train.sentences.values.toList.foldMap(getSentenceLength)
  // println("Train sentence lengths: " + getMetricsString(trainMetrics))
  // println(trainMetrics.histogramString(75))
}

object RankerPrinting {
  // def printInstanceExample(vi: Int, instance: ClauseInstance) = IO {
  //   println(Text.render(instance.sentenceTokens))
  //   println(instance.sentenceTokens(vi) + s" (${instance.sentenceTokens(vi)}) ")
  //   instance.clauses.sortBy(-_._2).foreach { case (clause, prob) =>
  //     println(f"$prob%.3f ${clause.getClauseString(instance.sentenceTokens, instance.verbInflectedForms)}")
  //   }
  // }

  // def printExamplesWithString(
  //   instances: Map[String, Map[Int, ClauseInstance]],
  //   verbString: String,
  //   string: String
  // ) = {
  //   instances
  //     .filter(t => Text.render(t._2.head._2.sentenceTokens).contains(string))
  //     .toList.flatMap(t => t._2.toList.filter(p => p._2.sentenceTokens(p._1) == verbString))
  //     .traverse(Function.tupled(printInstanceExample))
  // }

  // def printExamples(path: String, n: Int) = for {
  //   clauseInstances <- MetricsApp.readRankingPredictions(Paths.get(path))
  //   _ <- clauseInstances.iterator.take(n).toList.traverse { case (sid, verbs) =>
  //     verbs.toList.traverse(Function.tupled(printInstanceExample))
  //   }
  // } yield ()

  // val exStrings = List(
  //   "qualified" -> "The game was the first for Great Britain",
  //   "consumed" -> "The theory of supply and demand is an organizing principle",
  //   "seen" -> "New South Wales have seen"
  // )

  // def printChosenExamples(path: String) = for {
  //   clauseInstances <- MetricsApp.readRankingPredictions(Paths.get(path))
  //   _ <- exStrings.traverse { case (vs, s) => printExamplesWithString(clauseInstances, vs, s) }
  // } yield ()

  // printExamples(args(0), args(1).toInt).unsafeRunSync
  // printChosenExamples(args(0)).unsafeRunSync
}