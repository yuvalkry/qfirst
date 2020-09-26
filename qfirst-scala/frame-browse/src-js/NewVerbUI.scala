package qfirst.frame.browse

import qfirst.clause.ArgStructure
import qfirst.clause.ClauseResolution
import qfirst.frame._
import qfirst.frame.math._
import qfirst.model.eval.filterGoldNonDense
import qfirst.model.eval.filterOrigAnnotationRound

import cats.Id
import cats.Monoid
import cats.Order
import cats.data.NonEmptyList
import cats.data.NonEmptySet
import cats.implicits._

import scalajs.js
import org.scalajs.dom
import org.scalajs.dom.html
import org.scalajs.dom.ext.KeyCode

import scala.concurrent.ExecutionContext.Implicits.global

import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.CatsReact._
import japgolly.scalajs.react.MonocleReact._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.StateSnapshot

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._

import monocle._
import monocle.function.{all => Optics}
import monocle.macros._

import qasrl._
import qasrl.data._
import qasrl.labeling._

import qasrl.bank._

import qasrl.bank.service.DocumentService
import qasrl.bank.service.Search

import jjm.LowerCaseString
import jjm.OrWrapped
import jjm.ling.ESpan
import jjm.ling.Text
import jjm.ling.en.InflectedForms
import jjm.ling.en.VerbForm
import jjm.implicits._

import scala.collection.immutable.SortedSet

import radhoc._

import io.circe._

import scala.concurrent.Future

object NewVerbUI {

  implicit val callbackMonoid = new Monoid[Callback] {
    override def empty = Callback.empty
    override def combine(x: Callback, y: Callback) = x >> y
  }

  val S = VerbAnnStyles
  import HOCs._

  val VerbsFetch = new CacheCallContent[Unit, Map[InflectedForms, Int]]
  val VerbModelFetch = new CacheCallContent[InflectedForms, VerbClusterModel[InflectedForms, ClausalQuestion]]
  val SentencesFetch = new CacheCallContent[InflectedForms, Set[String]]
  val SentenceFetch = new CacheCallContent[String, SentenceInfo[InflectedForms, ClausalQuestion]]

  val DataFetch = new CacheCallContent[Unit, (DataIndex, Map[InflectedForms, Int])]
  val DocFetch = new CacheCallContent[DocumentId, Document]
  val DocOptFetch = new CacheCallContent[Option[DocumentId], Option[Document]]
  val SearchFetch = new CacheCallContent[Search.Query, Set[DocumentId]]
  // val FramesetFetch = new CacheCallContent[InflectedForms, VerbFrameset]
  val EvalItemFetch = new CacheCallContent[Int, ParaphrasingInfo]
  val IntLocal = new LocalState[Int]
  val VerbLocal = new LocalState[InflectedForms]
  val VerbModelLocal = new LocalState[Option[VerbClusterModel[InflectedForms, ClausalQuestion]]]
  val DocMetaOptLocal = new LocalState[Option[DocumentMetadata]]
  val SentOptLocal = new LocalState[Option[Sentence]]
  val QuestionLabelSetLocal = new LocalState[Set[QuestionLabel]]
  val IntSetLocal = new LocalState[Set[Int]]
  val FrameChoiceLocal = new LocalState[Set[(ArgumentId[ClausalQuestion], ArgStructure, ArgumentSlot)]]
  val QuestionSetLocal = new LocalState[Set[ArgumentId[ClausalQuestion]]]

  val (inflToString, inflFromString) = {
    import io.circe.syntax._
    import io.circe.parser.decode
    val printer = io.circe.Printer.noSpaces
    val toStr = (f: InflectedForms) => printer.pretty(f.asJson)
    val fromStr = (s: String) => decode[InflectedForms](s).right.get
    (toStr, fromStr)
  }

  def makeQidMap(sentenceId: String, verb: VerbEntry): Map[String, ArgumentId[ClausalQuestion]] = {
    val questionSlotsWithStrings = verb.questionLabels.toList.map { case (qString, qLabel) => qLabel.questionSlots -> qString }
    val questionToQid = questionSlotsWithStrings.map(_._2).zip(
      qfirst.clause.ClauseResolution.getResolvedFramePairs(
        verb.verbInflectedForms, questionSlotsWithStrings.map(_._1)
      ).map { case (frame, slot) => ArgumentId[ClausalQuestion](VerbId(sentenceId, verb.verbIndex), ClausalQuestion(frame, slot)) }
    ).toMap
    questionToQid
  }

  case class Props(
    verbService: VerbFrameService[OrWrapped[AsyncCallback, ?]],
    featureService: FeatureService[OrWrapped[AsyncCallback, ?], InflectedForms, ClausalQuestion],
    urlNavQuery: NavQuery,
    mode: RunMode
  )

  case class State()
  object State {
    val initial = State()
  }

  @Lenses case class ClusterCriterionControl(
    numClusters: Int,
    maxLoss: Double
  )
  object ClusterCriterionControl {
    def default = ClusterCriterionControl(1, 0.0)
  }

  val LocalClusterCriterionControl = new LocalState[ClusterCriterionControl]

  def clusterCriterionField(
    label: String,
    criterion: StateSnapshot[ClusterSplittingCriterion]
  ): VdomElement = {
    <.div(
      LocalClusterCriterionControl.make(ClusterCriterionControl.default) { criterionControl =>
        <.span(
          label + " ",
          <.span(S.disabledCriterionText.unless(criterion.value.isNumber))(
            "clusters",
            ^.onClick --> criterion.value.getLoss.foldMap(_ =>
              criterion.setState(
                ClusterSplittingCriterion.Number(criterionControl.value.numClusters)
              )
            )
          ),
          " / ",
          <.span(S.disabledCriterionText.unless(criterion.value.isLoss))(
            "loss",
            ^.onClick --> criterion.value.getNumber.foldMap(_ =>
              criterion.setState(
                ClusterSplittingCriterion.Loss(criterionControl.value.maxLoss)
              )
            )
          ),
          ": ",
          zoomStateP(criterion, ClusterSplittingCriterion.number).whenDefined(numClusters =>
            View.intArrowField(S.shortTextField)(
              None,
              numClusters,
              criterionControl.zoomStateL(ClusterCriterionControl.numClusters).setState(_)
            )
          ),
          zoomStateP(criterion, ClusterSplittingCriterion.loss)(Reusability.double(1e-3)).whenDefined(maxLoss =>
            View.doubleTextField(S.shortTextField)(
              None,
              maxLoss,
              criterionControl.zoomStateL(ClusterCriterionControl.maxLoss).setState(_)
            )
          )
        )
        // zoomStateP(criterion, ClusterSplittingCriterion.number) match {
        //   case Some(numClusters) => intArrowField(s"$label clusters", numClusters)
        //   case None => <.span(
        //     s"$label clusters: ",
        //     <.input(S.shortTextField)(
        //       ^.`type` := "text",
        //       ^.onClick --> criterion.setState(ClusterSplittingCriterion.Number(numClustersFallback))
        //     )
        //   )
        // },
        // zoomStateP(criterion, ClusterSplittingCriterion.loss)(Reusability.double(1e-3)) match {
        //   case Some(maxLoss) => doubleTextField(S.shortTextField)(Some(s"$label loss"), maxLoss)
        //   case None => <.span(
        //     s"$label loss: ",
        //     <.input(S.shortTextField)(
        //       ^.`type` := "text",
        //       ^.onClick --> criterion.setState(ClusterSplittingCriterion.Loss(maxLossFallback))
        //     )
        //   )
        // }
      }
    )
  }

  val queryKeywordHighlightLayer = Rgba(255, 255, 0, 0.4)

  val highlightLayerColors = List(
    // Rgba(255, 255,   0, 0.2), // yellow
    Rgba(  0, 128, 255, 0.1), // green-blue
    Rgba(255,   0, 128, 0.1), // magenta?
    Rgba( 64, 192,   0, 0.1), // something. idk
    Rgba(128,   0, 255, 0.1), // mystery
    Rgba(  0, 255, 128, 0.1)  // blue-green
  )

  val colspan = VdomAttr("colspan")

  val ArgStructureOptLocal = new LocalState[Option[(ArgStructure, ArgumentSlot)]]

  // def paraphrasingHighlightStyle(
  //   structure: (ArgStructure, ArgumentSlot),
  //   referenceOpt: Option[(ArgStructure, ArgumentSlot)],
  //   goldParaphrasesOpt: Option[VerbParaphraseLabels]
  // ) = {
  //   (if(referenceOpt.exists(_ == structure)) Some(S.argStructureChoiceIsChosen)
  //    else (referenceOpt, goldParaphrasesOpt).mapN { (reference, goldParaphrases) =>
  //      if(goldParaphrases.paraphrases.equal(reference, structure)) Some(S.argStructureChoiceIsCorrectParaphrase)
  //      else if(goldParaphrases.paraphrases.apart(reference, structure)) Some(S.argStructureChoiceIsIncorrectParaphrase)
  //      else None
  //    }.flatten
  //   ).whenDefined
  // }

  // // beh
  // def tagModForStructureLabel(
  //   structure: (ArgStructure, ArgumentSlot),
  //   argStructureChoiceOpt: StateSnapshot[Option[(ArgStructure, ArgumentSlot)]],
  //   argStructureHoverOpt: StateSnapshot[Option[(ArgStructure, ArgumentSlot)]],
  //   goldParaphrasesOpt: Option[StateSnapshot[VerbParaphraseLabels]]
  // ) = {
  //   TagMod(
  //     argStructureChoiceOpt.value match {
  //       case None => goldParaphrasesOpt.whenDefined(_ => ^.onClick --> argStructureChoiceOpt.setState(Some(structure)))
  //       case Some(`structure`) => ^.onClick --> argStructureChoiceOpt.setState(None)
  //       case Some(otherStructure) => goldParaphrasesOpt.whenDefined { goldParaphrases =>
  //         val paraphrases = goldParaphrases.zoomStateL(VerbParaphraseLabels.paraphrases)
  //           ^.onClick ==> (
  //             (e: ReactMouseEvent) => e.preventDefaultCB >> (
  //               if(e.altKey) {
  //                 if(paraphrases.value.apart(otherStructure, structure)) paraphrases.modState(_.unseparate(otherStructure, structure))
  //                 else paraphrases.modState(_.separate(otherStructure, structure))
  //               } else {
  //                 if(paraphrases.value.equal(otherStructure, structure)) paraphrases.modState(_.unequate(otherStructure, structure))
  //                 else paraphrases.modState(_.equate(otherStructure, structure))
  //               }
  //             )
  //           )
  //       }
  //     },
  //     paraphrasingHighlightStyle(structure, argStructureChoiceOpt.value.orElse(argStructureHoverOpt.value), goldParaphrasesOpt.map(_.value)),
  //     (^.onMouseMove --> argStructureHoverOpt.setState(Some(structure))).when(argStructureHoverOpt.value.isEmpty),
  //     ^.onMouseOut --> argStructureHoverOpt.setState(None)
  //   )
  // }

  def sentenceSelectionPane(
    sentenceIds: Vector[String], // TODO maybe sort?
    curSentenceId: StateSnapshot[String]
  ) = {
    val sentencesWord = if(sentenceIds.size == 1) "sentence" else "sentences"
    val sentenceCountLabel = s"${sentenceIds.size} $sentencesWord"

    <.div(S.sentenceSelectionPaneContainer)(
      <.div(S.sentenceCountLabel)(
        <.span(S.sentenceCountLabelText)(
          sentenceCountLabel
        )
      ),
      <.div(S.sentenceSelectionPane)(
        sentenceIds.toVdomArray { sentenceId =>
          <.div(S.sentenceSelectionEntry)(
            ^.key := sentenceId,
            if(sentenceId == curSentenceId.value) S.currentSelectionEntry else S.nonCurrentSelectionEntry,
            ^.onClick --> curSentenceId.setState(sentenceId),
            <.span(S.sentenceSelectionEntryText)(
              sentenceId
              // Text.render(sentence.sentenceTokens) // TODO maybe show sentence text
            )
          )
        }
      )
    )
  }


  // // var qidToFramesCache = Map.empty[ArgumentId[ClausalQuestion], Set[(Frame, ArgumentSlot)]]
  def sentenceDisplayPane(
    verb: InflectedForms,
    sentence: SentenceInfo[InflectedForms, ClausalQuestion]
  ) = {
    val sortedVerbs = sentence.verbs.values.toList.sortBy(_.index)
    IntSetLocal.make(initialValue = Set.empty[Int]) { highlightedVerbIndices =>
      // val answerSpansWithColors = for {
      //   (verbIndex, index) <- sortedVerbIndices.zipWithIndex
      //   if highlightedVerbIndices.value.contains(verb.verbIndex)
      //   question <- verb.questionLabels.values.toList
      //   answerLabel <- question.answerJudgments
      //   Answer(spans) <- answerLabel.judgment.getAnswer.toList
      //   span <- spans.toList
      // } yield span -> highlightLayerColors(index % highlightLayerColors.size)
      // val verbColorMap = sortedVerbs
      //   .zipWithIndex.map { case (verb, index) =>
      //     verb.verbIndex -> highlightLayerColors(index % highlightLayerColors.size)
      // }.toMap

      <.div(S.sentenceDisplayPane)(
        <.div(S.sentenceTextContainer)(
          <.span(S.sentenceText)(
            Text.render(sentence.tokens)
            // View.renderSentenceWithHighlights(
            //   sentence.tokens,
            //   View.RenderWholeSentence(answerSpansWithColors),
            //   verbColorMap.collect { case (verbIndex, color) =>
            //     verbIndex -> (
            //       (v: VdomTag) => <.a(
            //         S.verbAnchorLink,
            //         ^.href := s"#verb-$verbIndex",
            //         v(
            //           ^.color := color.copy(a = 1.0).toColorStyleString,
            //           ^.fontWeight := "bold",
            //           ^.onMouseMove --> (
            //             if(highlightedVerbIndices.value == Set(verbIndex)) {
            //               Callback.empty
            //             } else highlightedVerbIndices.setState(Set(verbIndex))
            //           ),
            //           ^.onMouseOut --> highlightedVerbIndices.setState(searchedVerbIndices)
            //         )
            //       )
            //     )
            //   }
            // )
          )
        ),
        <.div(S.verbEntriesContainer)(
          sortedVerbs.toVdomArray { verb =>
            <.div(S.verbEntryDisplay)(
              // <.div(
              //   <.a(
              //     ^.name := s"verb-${verb.verbIndex}",
              //     ^.display := "block",
              //     ^.position := "relative",
              //     ^.visibility := "hidden"
              //   )
              // ),
              <.div(S.verbHeading)(
                <.span(S.verbHeadingText)(
                  // ^.color := color.copy(a = 1.0).toColorStyleString,
                  // ^.onClick --> (
                  //   navQuery.setState(
                  //     DatasetQuery(
                  //       verb.verbInflectedForms.allForms.toSet,
                  //       Set(SentenceId.fromString(curSentence.sentenceId).documentId.toString.lowerCase),
                  //       Set(curSentence.sentenceId.lowerCase)
                  //     )
                  //   )
                  // ),
                  sentence.tokens(verb.index)
                )
              ),
              <.table(S.verbQAsTable)( // arg table
                <.tbody(S.verbQAsTableBody)(
                  verb.args.toVector.sorted.toVdomArray(arg =>
                    <.tr(<.td(arg.questionString))
                  )
                )
              )
            )(
              S.hoverHighlightedVerbTable.when(highlightedVerbIndices.value == Set(verb.index)),
              ^.key := verb.index,
              ^.onMouseMove --> (
                if(highlightedVerbIndices.value == Set(verb.index)) {
                  Callback.empty
                } else highlightedVerbIndices.setState(Set(verb.index))
              ),
              ^.onMouseOut --> highlightedVerbIndices.setState(Set.empty[Int])
            )
          }
        )
      )
    }
  }

  val textFocusingRef = Ref[html.Element]

  def unsafeListAt[A](index: Int) =
    Lens[List[A], A](s => s(index))(a => s => s.updated(index, a))

  def lensProduct[A, B, C](l1: Lens[A, B], l2: Lens[A, C]) =
    Lens[A, (B, C)](a => l1.get(a) -> l2.get(a)) {
      case (b, c) => a => l2.set(c)(l1.set(b)(a))
    }

  def throughOption[A, B](l: Lens[A, Option[B]])(implicit M: Monoid[B]): Lens[A, B] = {
    Lens[A, B](a => l.get(a).combineAll)(
      b => a => if(b == M.empty) l.set(None)(a) else l.set(Some(b))(a)
    )
  }

  val DoubleLocal = new LocalState[Double]

  // TODO color-code the answer spans by _question_ instead of by verb

  val GoldParaphrasesLocal = new LocalState[VerbParaphraseLabels]
  val ParaphrasingFilterLocal = new LocalState[ParaphrasingFilter]

  def zoomStateP[A, B](
    s: StateSnapshot[A],
    prism: Prism[A, B])(
    implicit ev: Reusability[B]
  ): Option[StateSnapshot[B]] = {
    prism.getOption(s.value).map { b =>
      StateSnapshot.withReuse.prepare[B]((bOpt, cb) => s.setStateOption(bOpt.map(prism.reverseGet), cb))(b)
    }
  }

  def zoomOpt[A](
    s: StateSnapshot[Option[A]])(
    implicit r: Reusability[A]
  ): Option[StateSnapshot[A]] = {
    s.value.map { a =>
      StateSnapshot.withReuse.prepare[A](s.setState)(a)
    }
  }

  def headerContainer(
    sortedVerbCounts: List[(InflectedForms, Int)],
    curVerb: StateSnapshot[InflectedForms]
  ) = <.div(S.headerContainer)(
    <.select(S.verbDropdown)(
      ^.value := inflToString(curVerb.value),
      ^.onChange ==> ((e: ReactEventFromInput) =>
        curVerb.setState(inflFromString(e.target.value))
      ),
      sortedVerbCounts.toVdomArray { case (forms, count) =>
        <.option(
          ^.key := inflToString(forms),
          ^.value := inflToString(forms),
          f"$count%5d ${forms.allForms.mkString(", ")}%s"
        )
      }
    )
  )

  val defaultParaphrasingFilter = ParaphrasingFilter(
    ClusterSplittingCriterion.Number(1),
    ClusterSplittingCriterion.Number(5),
    0.02, 0.3
  )

  def makeSurrogateFrame(structure: ArgStructure, forms: InflectedForms, useModal: Boolean) = {
    Frame(
      forms, structure.args,
      tense = (if(useModal) Modal("might".lowerCase) else PresentTense),
      isPassive = structure.isPassive,
      isPerfect = false,
      isProgressive = false,
      isNegated = false
    )
  }

  def frameContainer(
    props: Props,
    cachedParaphrasingFilter: StateSnapshot[ParaphrasingFilter],
    verb: InflectedForms
  ) = {
    VerbModelFetch.make(
      request = verb,
      sendRequest = verb => props.verbService.getModel(verb)) {
      case VerbModelFetch.Loading => <.div(S.loadingNotice)("Loading verb clusters...")
      case VerbModelFetch.Loaded(model) =>
        val numVerbInstances = model.verbClusterTree.size.toInt
        ParaphrasingFilterLocal.make(initialValue = cachedParaphrasingFilter.value) { paraphrasingFilter =>
          def isClauseProbabilityAcceptable(p: Double) = true || p >= 0.01 || paraphrasingFilter.value.minClauseProb <= p

          val verbTrees = paraphrasingFilter.value.verbCriterion.splitTree(model.verbClusterTree)
          val verbIndices = verbTrees.zipWithIndex.flatMap { case (tree, index) =>
            tree.values.flatMap(verbIds => verbIds.toVector.map(_ -> index))
          }.toMap
          // TODO: split down to how it was during verb clustering, then *possibly* re-cluster.
          val argTrees = model.argumentClusterTreeOpt
            .map(_.groupBy(argId => verbIndices(argId.verbId)))
            .getOrElse(Map())


          <.div(S.framesetContainer)(
            <.div(S.paraphrasingFilterDisplay)(
              clusterCriterionField("Verb", paraphrasingFilter.zoomStateL(ParaphrasingFilter.verbCriterion)),
              clusterCriterionField("Question", paraphrasingFilter.zoomStateL(ParaphrasingFilter.questionCriterion)),
              <.div(View.sliderField("Clause", 0.0, 1.0, paraphrasingFilter.zoomStateL(ParaphrasingFilter.minClauseProb))),
              <.div(View.sliderField("Paraphrase", 0.0, 1.0, paraphrasingFilter.zoomStateL(ParaphrasingFilter.minParaphrasingProb))),
              <.div(
                <.button(
                  "cache",
                  ^.onClick --> cachedParaphrasingFilter.setState(paraphrasingFilter.value)
                )
              )
              // <.div(f"Max Verb Loss/instance: ${maxLoss / numInstances}%.3f")
            ),
            <.div(S.frameSpecDisplay, S.scrollPane) {
              verbTrees.zipWithIndex.toVdomArray { case (verbTree, frameIndex) =>
                val argTree = argTrees(frameIndex)
                val roleTrees = paraphrasingFilter.value.questionCriterion.splitTree(argTree)
                val numInstances = verbTree.size.toInt
                val frameProb = numInstances.toDouble / numVerbInstances
                val isFrameChosen = false // TODO
                <.div(S.frameContainer, S.chosenFrameContainer.when(isFrameChosen))(
                  ^.key := "frame-" + frameIndex.toString,
                  <.div(S.frameHeading, S.chosenFrameHeading.when(isFrameChosen))(
                    <.span(S.frameHeadingText)(
                      f"Frame $frameIndex%s (${frameProb}%.4f)"
                    )
                  ),
                  <.div(S.clauseSetDisplay)(
                    roleTrees.toVdomArray(roleTree =>
                      <.div(s"Arg: ${roleTree.size} instances.")
                    )
                  )
                )
              }

              // val frameList = frameset.frames.zipWithIndex
              // frameList.toVdomArray { case (frame, frameIndex) =>
              //   val isFrameChosen = {
              //     val bools = for {
              //       sentence <- sentenceOpt.toList
              //       verbIndex <- verbIndices.toList
              //     } yield frame.verbIds.contains(VerbId(sentence.sentenceId, verbIndex))
              //     bools.exists(identity)
              //   }
              //   val frameLens = VerbFrameset.frames
              //     .composeLens(unsafeListAt[VerbFrame](frameIndex))
              //   val roleClusters = paraphrasingFilter.value.questionCriterion.splitTree(frame.questionClusterTree)
              //   // clause -> slot -> role -> sorted qids
              //   val argMappings: Map[ArgStructure, Map[ArgumentSlot, Map[Int, SortedSet[ArgumentId[ClausalQuestion]]]]] = {
              //     roleClusters.zipWithIndex.foldMap { case (tree, roleIndex) =>
              //       tree.unorderedFoldMap { case qid @ ArgumentId(_, question) =>
              //         Map(question.clauseTemplate -> Map(question.slot -> Map(roleIndex -> SortedSet(qid))))
              //       }
              //     }
              //   }
              //   val baseArgSigils = Vector("X", "Y", "Z", "A", "B", "C")
              //   val argSigils = baseArgSigils ++ (2 to 9).toVector.flatMap(i =>
              //     baseArgSigils.map(_ + i.toString)
              //   )
              //   val getArgSigil = argSigils(_)

              //   val frameSentenceDocPairsOpt = (docIdToDocMetaOpt, sentenceOpt).mapN { (docIdToDocMeta, sentence) =>
              //     (frame.verbIds.map(vid => SentenceId.fromString(vid.sentenceId)).toSet + SentenceId.fromString(sentence.sentenceId)).toList
              //       .map(sid => sid -> docIdToDocMeta(sid.documentId))
              //       .sorted(
              //         Order.catsKernelOrderingForOrder(
              //           Order.whenEqual[(SentenceId, DocumentMetadata)](
              //             Order.by[(SentenceId, DocumentMetadata), String](_._2.title),
              //             Order.by[(SentenceId, DocumentMetadata), SentenceId](_._1)
              //           )
              //         )
              //       )
              //   }

              //   def makeNavQueryForSentenceIndexOpt(index: Int) = {
              //     frameSentenceDocPairsOpt.map { allSentencesForFrame =>
              //       val sid = allSentencesForFrame(index)._1
              //       val sidStr = SentenceId.toString(sid)
              //       val docIdStr = sid.documentId.toString
              //       DatasetQuery(verbInflectedForms.allForms.toSet, Set(docIdStr.lowerCase), Set(sidStr.lowerCase))
              //     }
              //   }
              //   val curSentenceIndexOpt = (frameSentenceDocPairsOpt, sentenceIdOpt).mapN { (frameSentenceDocPairs, sentenceId) =>
              //     frameSentenceDocPairs
              //       .zipWithIndex
              //       .find(t => t._1._1 == sentenceId)
              //       .map(_._2)
              //   }.flatten
              //   def makePrevQuery = (frameSentenceDocPairsOpt, curSentenceIndexOpt).mapN { (frameSentenceDocPairs, curSentenceIndex) =>
              //     makeNavQueryForSentenceIndexOpt(
              //       (curSentenceIndex - 1 + frameSentenceDocPairs.size) % frameSentenceDocPairs.size
              //     )
              //   }.flatten
              //   def makeNextQuery = (frameSentenceDocPairsOpt, curSentenceIndexOpt).mapN { (frameSentenceDocPairs, curSentenceIndex) =>
              //     makeNavQueryForSentenceIndexOpt(
              //       (curSentenceIndex + 1) % frameSentenceDocPairs.size
              //     )
              //   }.flatten

              //   def goToPrev(ids: SortedSet[ArgumentId[ClausalQuestion]]) = {
              //     sentenceIdOpt.foldMap { sentenceId =>
              //       val querySentenceIds = {
              //         val sids = ids.map(qid => SentenceId.fromString(qid.verbId.sentenceId))
              //         (sids + sentenceId).toList
              //       }
              //       (querySentenceIds.last :: querySentenceIds).zip(querySentenceIds).find(
              //         _._2 == sentenceId
              //       ).map(_._1).foldMap(newSid =>
              //         navQuery.setState(
              //           DatasetQuery(
              //             verbInflectedForms.allForms.toSet,
              //             Set(newSid.documentId.toString.lowerCase),
              //             Set(SentenceId.toString(newSid).lowerCase)
              //           )
              //         )
              //       )
              //     }
              //   }
              //   def goToNext(ids: SortedSet[ArgumentId[ClausalQuestion]]) = {
              //     sentenceIdOpt.foldMap { sentenceId =>
              //       val querySentenceIds = {
              //         val sids = ids.map(qid => SentenceId.fromString(qid.verbId.sentenceId))
              //         (sids + sentenceId).toList
              //       }
              //       (querySentenceIds.last :: querySentenceIds).zip(querySentenceIds).find(
              //         _._1 == sentenceId
              //       ).map(_._2).foldMap(newSid =>
              //         navQuery.setState(
              //           DatasetQuery(
              //             verbInflectedForms.allForms.toSet,
              //             Set(newSid.documentId.toString.lowerCase),
              //             Set(SentenceId.toString(newSid).lowerCase)
              //           )
              //         )
              //       )
              //     }
              //   }
              //   def sigilNavigationMod(ids: SortedSet[ArgumentId[ClausalQuestion]]) = TagMod(
              //     ^.onClick ==> ((e: ReactMouseEvent) =>
              //       if(e.altKey) goToPrev(ids) else goToNext(ids)
              //     )
              //   )

              //   <.div(S.frameContainer, S.chosenFrameContainer.when(isFrameChosen))(
              //     ^.key := "clause-set-" + frameIndex.toString,
              //     <.div(S.frameHeading, S.chosenFrameHeading.when(isFrameChosen))(
              //       <.span(S.frameHeadingText)(
              //         f"Frame $frameIndex%s (${frame.probability}%.4f)"
              //       ),
              //       makePrevQuery.whenDefined(goToPrev =>
              //         <.span(S.prevFrameInstanceText)(
              //           " (prev)",
              //           ^.onClick --> navQuery.setState(goToPrev))
              //       ),
              //       makeNextQuery.whenDefined(goToNext =>
              //         <.span(S.prevFrameInstanceText)(
              //           " (next)",
              //           ^.onClick --> navQuery.setState(goToNext))
              //       )
              //     ),
              //     <.div(S.clauseSetDisplay)(
              //       frame.clauseTemplates.zipWithIndex
              //         .filter(p => isClauseProbabilityAcceptable(p._1.probability))
              //         .sortBy(-_._1.probability)
              //         .toVdomArray { case (frameClause, clauseIndex) =>
              //           val numQuestions = argMappings(frameClause.args).unorderedFoldMap(_.unorderedFoldMap(_.size))
              //           val surrogateFrame = makeSurrogateFrame(frameClause.args, verbInflectedForms, useModal = false)

              //           <.div(S.clauseDisplay, S.matchingClause.when(predictedParaphraseClauseTemplatesOpt.exists(_.contains(frameClause.args))))(
              //             ^.key := "clause-" + clauseIndex.toString,
              //             <.div(
              //               goldParaphrasesOpt.whenDefined { goldParaphrases =>
              //                 val clauseCorrectLens = VerbParaphraseLabels.correctClauses.composeLens(Optics.at(frameClause.args))
              //                 val clauseIncorrectLens = VerbParaphraseLabels.incorrectClauses.composeLens(Optics.at(frameClause.args))
              //                 val clauseCorrectness = goldParaphrases.zoomStateL(lensProduct(clauseCorrectLens, clauseIncorrectLens))
              //                   <.div(S.goldClauseMarkerDisplay)(
              //                     <.label(S.goldClauseCheckLabel)(
              //                       <.input(
              //                         ^.`type` := "checkbox",
              //                         ^.value := clauseCorrectness.value._1,
              //                         ^.onChange ==> ((e: ReactEventFromInput) =>
              //                           if(clauseCorrectness.value._1) clauseCorrectness.setState(false -> false)
              //                           else clauseCorrectness.setState(true -> false)
              //                         )
              //                       ),
              //                       <.div(S.goldClauseCheck, S.goldClauseCheckCorrect.when(clauseCorrectness.value._1))
              //                     ),
              //                     <.label(S.goldClauseXLabel)(
              //                       <.input(
              //                         ^.`type` := "checkbox",
              //                         ^.value := clauseCorrectness.value._2,
              //                         ^.onChange ==> ((e: ReactEventFromInput) =>
              //                           if(clauseCorrectness.value._2) clauseCorrectness.setState(false -> false)
              //                           else clauseCorrectness.setState(false -> true)
              //                         )
              //                       ),
              //                       <.div(S.goldClauseX, S.goldClauseXIncorrect.when(clauseCorrectness.value._2))
              //                     )
              //                   )
              //               },
              //               <.span(S.shiftedClauseTemplateDisplay.when(goldParaphrasesOpt.nonEmpty))(
              //                 <.span(f"(${frameClause.probability}%.2f) "),
              //                 surrogateFrame.clausesWithArgMarkers.head.zipWithIndex.map {
              //                   case (Left(s), i) => <.span(^.key := s"frame-clause-$i", s)
              //                   case (Right(argSlot), i) => <.span(
              //                     ^.key := s"frame-clause-$i",
              //                     BoolLocal.make(initialValue = false) { isEditingSlot =>
              //                       val sigilSuffix = surrogateFrame.args.get(argSlot).get match {
              //                         case Noun(_) => ""
              //                         case Prep(p, _) =>
              //                           if(p.toString.contains("doing")) "[ng]"
              //                           else if(p.toString.contains(" do")) "[inf]"
              //                           else ""
              //                         case Locative => "[where]"
              //                       }
              //                       val genericGoldMatchingMod = S.genericGoldMatchingArgMarker.when(
              //                         goldStructureRelationOpt.exists(_.preimage(frameClause.args -> argSlot).nonEmpty)
              //                       )
              //                       val selectionMod = tagModForStructureLabel(
              //                         frameClause.args -> argSlot, argStructureChoiceOpt, argStructureHoverOpt, goldParaphrasesOpt
              //                       )
              //                       def getSigilSpan(roleIndex: Int, ids: SortedSet[ArgumentId[ClausalQuestion]]): VdomElement = {
              //                         val goldMatchingMod = S.goldMatchingArgMarker.when(
              //                           sentenceOpt.exists(sent => ids.exists(_.verbId.sentenceId == sent.sentenceId))
              //                         )

              //                         <.span(
              //                           S.argSigil, genericGoldMatchingMod,
              //                           goldMatchingMod, /* predMatchingMod, */
              //                           S.sigilProportionalColor((ids.size.toDouble / numQuestions * 20).toInt),
              //                           sigilNavigationMod(ids))(
              //                           getArgSigil(roleIndex) + sigilSuffix
              //                         )
              //                       }
              //                       def getRoleSpan(roleCounts: Map[Int, SortedSet[ArgumentId[ClausalQuestion]]]) = {
              //                         <.span(selectionMod)(
              //                           if(roleCounts.size == 1) {
              //                             val (roleIndex, ids) = roleCounts.head
              //                             getSigilSpan(roleIndex, ids)
              //                           } else {
              //                             val argSigils = roleCounts.toList.map(Function.tupled(getSigilSpan(_, _)))
              //                               <.span(
              //                                 "[",
              //                                 argSigils.map(Vector(_))
              //                                   .intercalate(Vector(<.span(" / ")))
              //                                   // .zipWithIndex
              //                                   // .map { case (x, i) => x(^.key := s"sigil-$i") }
              //                                   .toVdomArray,
              //                                 "]"
              //                               )
              //                           }
              //                         )
              //                       }

              //                       argMappings.get(frameClause.args).flatMap(_.get(argSlot)).map { roleCounts =>
              //                         getRoleSpan(roleCounts)
              //                       }.getOrElse {
              //                         <.span(S.argPlaceholder, genericGoldMatchingMod, /* predMatchingMod, */ selectionMod){
              //                           val prefix = surrogateFrame.args.get(argSlot) match {
              //                             case Some(Prep(p, _)) if p.endsWith(" doing".lowerCase) => "doing "
              //                             case Some(Prep(p, _)) if p == "do".lowerCase || p.endsWith(" do".lowerCase) => "do "
              //                             case _ => ""
              //                           }
              //                           prefix + surrogateFrame.args.get(argSlot).get.placeholder.mkString(" ")
              //                         }
              //                       }
              //                       // TODO integrate these into the common tag mod
              //                       // val predMatchingMod = S.predMatchingArgMarker.when(
              //                       //   predStructureRelationOpt.exists(_.preimage(frameClause.args -> argSlot).nonEmpty)
              //                       // )

              //                       // argMappings.get(frameClause.args).flatMap(_.get(argSlot)).map(s =>
              //                       //   <.span(S.argSigil, goldMatchingMod, /* predMatchingMod, */selectionMod)(s + sigilSuffix): VdomElement
              //                       // ).getOrElse(
              //                       //   <.span(S.argPlaceholder, goldMatchingMod, /* predMatchingMod, */ selectionMod){
              //                       //     val prefix = surrogateFrame.args.get(argSlot) match {
              //                       //       case Some(Prep(p, _)) if p.endsWith(" doing".lowerCase) => "doing "
              //                       //       case Some(Prep(p, _)) if p == "do".lowerCase || p.endsWith(" do".lowerCase) => "do "
              //                       //       case _ => ""
              //                       //     }
              //                       //     prefix + surrogateFrame.args.get(argSlot).get.placeholder.mkString(" ")
              //                       //   }
              //                       // )
              //                     }
              //                   )
              //                 }.map(List(_)).intercalate(List(<.span(" "))).zipWithIndex.toVdomArray(p => p._1(^.key := s"frame-clause-tok-${p._2}"))
              //               ),
              //               <.div(S.adverbialRoles)(
              //                 argMappings.get(frameClause.args).whenDefined { argSlotToRoleCounts =>
              //                   argSlotToRoleCounts.toVector.collect { case (argSlot @ Adv(wh), roleCounts) =>
              //                     val genericGoldMatchingMod = S.genericGoldMatchingArgMarker.when(
              //                       goldStructureRelationOpt.exists(_.preimage(frameClause.args -> argSlot).nonEmpty)
              //                     )
              //                     val selectionMod = tagModForStructureLabel(
              //                       frameClause.args -> argSlot, argStructureChoiceOpt, argStructureHoverOpt, goldParaphrasesOpt
              //                     )
              //                     def getSigilSpan(roleIndex: Int, ids: SortedSet[ArgumentId[ClausalQuestion]]): VdomElement = {
              //                       val goldMatchingMod = S.goldMatchingArgMarker.when(
              //                         sentenceOpt.exists(sent => ids.exists(_.verbId.sentenceId == sent.sentenceId))
              //                       )

              //                       <.span(
              //                         S.argSigil, genericGoldMatchingMod, goldMatchingMod, /* predMatchingMod, */selectionMod,
              //                         S.sigilProportionalColor((ids.size.toDouble / numQuestions * 20).toInt),
              //                         sigilNavigationMod(ids))(
              //                         getArgSigil(roleIndex)
              //                       )
              //                     }
              //                     def getRoleSpan(roleCounts: Map[Int, SortedSet[ArgumentId[ClausalQuestion]]]) = {
              //                       if(roleCounts.size == 1) {
              //                         val (roleIndex, ids) = roleCounts.head
              //                         getSigilSpan(roleIndex, ids)
              //                       } else {
              //                         val argSigils = roleCounts.toList.map(Function.tupled(getSigilSpan(_, _)))
              //                           <.span(
              //                             "[",
              //                             argSigils.map(Vector(_))
              //                               .intercalate(Vector(<.span(" / ")))
              //                               // .zipWithIndex
              //                               // .map { case (x, i) => x(^.key := s"sigil-$i") }
              //                               .toVdomArray,
              //                             "]"
              //                           )
              //                       }
              //                     }

              //                     <.span(S.adverbialRole)(
              //                       <.span(S.adverbialRoleAdverb)(s"$wh: "),
              //                       getRoleSpan(roleCounts)
              //                     )
              //                   }.toVdomArray
              //                 }
              //               )
              //             )
              //           )
              //         }
              //     )
              //   )
              // }
            }
          )
        }
    }
  }

  def dataContainer(
    featureService: FeatureService[OrWrapped[AsyncCallback, ?], InflectedForms, ClausalQuestion],
    curVerb: StateSnapshot[InflectedForms]
  ) = {
    <.div(S.dataContainer)(
      SentencesFetch.make(
        request = curVerb.value,
        sendRequest = verb => featureService(FeatureReq.Sentences(verb))) {
        case SentencesFetch.Loading => <.div(S.loadingNotice)("Loading sentence IDs...")
        case SentencesFetch.Loaded(sentenceIds) =>
          // TODO use sorted set
          val initSentenceId = sentenceIds.head
          StringLocal.make(initialValue = initSentenceId) { curSentenceId =>
            <.div(S.dataContainer)(
              sentenceSelectionPane(
                sentenceIds.toVector,
                curSentenceId
              ),
              SentenceFetch.make(
                request = curSentenceId.value,
                sendRequest = sid => featureService(FeatureReq.Sentence(sid))) {
                case SentenceFetch.Loading => <.div(S.loadingNotice)("Loading sentence...")
                case SentenceFetch.Loaded(sentenceInfo) =>
                  sentenceDisplayPane(
                    curVerb.value,
                    sentenceInfo
                  )
              }
            )
          }
      }
    )
  }

  class Backend(scope: BackendScope[Props, State]) {

    def render(props: Props, state: State) = {
      VerbsFetch.make(request = (), sendRequest = _ => props.verbService.getVerbs) {
        case VerbsFetch.Loading => <.div(S.loadingNotice)("Waiting for verb data...")
        case VerbsFetch.Loaded(verbCounts) =>
          val sortedVerbCounts = verbCounts.toList.sorted(
            Order.catsKernelOrderingForOrder(
              Order.whenEqual(
                Order.by[(InflectedForms, Int), Int](-_._2),
                Order.by[(InflectedForms, Int), String](p => inflToString(p._1))
              )
            )
          )
          val initVerb = sortedVerbCounts(scala.math.min(sortedVerbCounts.size - 1, 10))._1
          ParaphrasingFilterLocal.make(initialValue = defaultParaphrasingFilter) { cachedParaphrasingFilter =>
            VerbLocal.make(initialValue = initVerb) { curVerb =>
              <.div(S.mainContainer)(
                headerContainer(sortedVerbCounts, curVerb),
                <.div(S.dataContainer)(
                  frameContainer(props, cachedParaphrasingFilter, curVerb.value),
                  dataContainer(props.featureService, curVerb)
                )
              )
            }
          }
      }
    }
  }

  val Component = ScalaComponent.builder[Props]("VerbAnnClient")
    .initialState(State.initial)
    .renderBackend[Backend]
    .build

}
