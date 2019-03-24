package qfirst

import qfirst.frames.implicits._

import cats._
import cats.implicits._

import nlpdata.datasets.wiktionary.InflectedForms
import nlpdata.datasets.wiktionary.VerbForm
import nlpdata.util.Text
import nlpdata.util.LowerCaseStrings._

import qasrl._
import qasrl.data._
import qasrl.labeling._
import qasrl.util._
import qasrl.util.implicits._

import io.circe.generic.JsonCodec

object ClauseResolution {

  @JsonCodec case class ArgStructure(
    args: DependentMap[ArgumentSlot.Aux, Id],
    isPassive: Boolean
  ) {
    def forgetAnimacy = {
      val newArgs = args.keys.foldLeft(DependentMap.empty[ArgumentSlot.Aux, Id]) {
        (m, k) => k match {
          case Subj   => m.put(Subj, Noun(false))
          case Obj    => m.put(Obj, Noun(false))
          case Obj2  => m.put(
            Obj2, args.get(Obj2).get match {
              case Noun(_) => Noun(false)
              case Prep(p, Some(Noun(_))) => Prep(p, Some(Noun(false)))
              case x => x
            }
          )
          case Adv(wh) => m.put(Adv(wh), args.get(Adv(wh)).get)
        }
      }
      this.copy(args = newArgs)
    }
  }

  def getFramesWithAnswerSlots(verbInflectedForms: InflectedForms, question: String) = {
    val questionTokensIsh = question.init.split(" ").toVector.map(_.lowerCase)
    val qPreps = questionTokensIsh.filter(TemplateStateMachine.allPrepositions.contains).toSet
    val qPrepBigrams = questionTokensIsh
      .sliding(2)
      .filter(_.forall(TemplateStateMachine.allPrepositions.contains))
      .map(_.mkString(" ").lowerCase)
      .toSet
    val stateMachine =
      new TemplateStateMachine(Vector(), verbInflectedForms, Some(qPreps ++ qPrepBigrams))
    val template = new QuestionProcessor(stateMachine)
    val framesWithAnswerSlots = template.processStringFully(question) match {
      case Left(QuestionProcessor.AggregatedInvalidState(_, _)) =>
        println("Failed! " + question)
        ???
      case Right(goodStates) => goodStates.toList.collect {
        case QuestionProcessor.CompleteState(_, frame, answerSlot) =>
          frame -> answerSlot
      }.toSet
    }
    framesWithAnswerSlots
  }


  def locallyResolve(framePairSets: List[Set[(Frame, ArgumentSlot)]]) = {
    val framePseudoCounts = framePairSets.foldMap { framePairSet =>
      val frames = framePairSet.map(_._1).toList
      frames.map(_ -> (1.0 / frames.size)).toMap
    }
    framePairSets.map { framePairSet =>
      framePairSet.toList.maximaBy((p: (Frame, ArgumentSlot)) => framePseudoCounts(p._1)).toSet
    }
  }

  def fallbackResolve(slots: SlotBasedLabel[VerbForm], framePairSet: Set[(Frame, ArgumentSlot)]) = {
    classifyAmbiguity(slots, framePairSet) match {
      case "unambiguous" => framePairSet.head
      case "prepositional" => framePairSet.find(_._2 == Obj2).get
      case "where" => framePairSet.find(_._2 == Adv("where".lowerCase)).get
      case "ditransitive" => slots.wh.toString match {
        case "what" => framePairSet.find(_._2 == Obj2).get
        case "who" => framePairSet.find(_._2 == Obj).get
      }
      case "other" => framePairSet.head
    }
  }

  def classifyAmbiguity(slots: SlotBasedLabel[VerbForm], frameSet: Set[(Frame, ArgumentSlot)]) = {
    val isUnambiguous = frameSet.size == 1
    val isPrepositionalObj = (
      slots.obj.isEmpty && slots.prep.nonEmpty && slots.obj2.isEmpty &&
        frameSet.map(_._2) == Set(Obj, Obj2)
    )
    val isWhereAmb = (
      slots.wh == "where".lowerCase && frameSet.map(_._2) == Set(Adv("where".lowerCase), Obj2)
    )
    val isDitransitiveAmb = (
      Set("who", "what").contains(slots.wh.toString) &&
        ((slots.obj.nonEmpty && slots.obj2.isEmpty) || (slots.obj.isEmpty && slots.obj2.nonEmpty)) &&
        frameSet.map(_._2) == Set(Obj, Obj2)
    )
    if(isUnambiguous) "unambiguous"
    else if(isPrepositionalObj) "prepositional"
    else if(isWhereAmb) "where"
    else if(isDitransitiveAmb) "ditransitive"
    else "other"
  }

  def getResolvedFramePairs(verbInflectedForms: InflectedForms, qLabels: List[QuestionLabel]) = {
    val frameSets = qLabels.map(_.questionString)
      .map(getFramesWithAnswerSlots(verbInflectedForms, _))
    val locallyResolvedFramePairSets = locallyResolve(frameSets)
    qLabels.map(_.questionSlots)
      .zip(locallyResolvedFramePairSets)
      .map(Function.tupled(fallbackResolve(_, _)))
  }

  def getClauseTemplate(frame: Frame) = {
    ArgStructure(frame.args, frame.isPassive).forgetAnimacy
  }

}
