package qfirst.frame
import qfirst.model.eval.protocols.SimpleQAs

import java.nio.file._

import qasrl.data.Dataset
import qasrl.ArgumentSlot
import qasrl.labeling.SlotBasedLabel

import qasrl.bank.Data
import qasrl.bank.FullData

import jjm.ling.ESpan
import jjm.ling.en.InflectedForms
import jjm.ling.en.VerbForm
import jjm.io.FileUtil
import jjm.implicits._

import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._

import fs2.Stream

import io.circe.generic.JsonCodec
import io.circe.{Encoder, Decoder}

import monocle.function.{all => Optics}

import qfirst.clause.ArgStructure
import qfirst.clause.ClauseResolution

import freelog._
import freelog.implicits._

abstract class Features[VerbType : Encoder : Decoder, Instance](
  mode: RunMode)(
  implicit cs: ContextShift[IO],
  Log: EphemeralTreeLogger[IO, String]) {

  implicit protected val runMode = mode

  val instances: RunDataCell[Instances[VerbType, Set[Instance]]]

  val sentences: RunDataCell[NonMergingMap[String, Vector[String]]]

  protected val createDir = (path: Path) => IO(!Files.exists(path))
    .ifM(IO(Files.createDirectories(path)), IO.unit)

  protected val rootDir: Path

  def outDir = IO.pure(rootDir.resolve("out")).flatTap(createDir)

  // for use by frame induction etc.
  def modelDir = outDir.map(
    _.resolve("models").resolve(mode.toString)
  ).flatTap(createDir)

  // for inputs to feature computation
  protected def inputDir = rootDir.resolve("input")

  // for caching features that take a while to compute
  protected def cacheDir = IO.pure(rootDir.resolve("cache")).flatTap(createDir)

  // question and verb IDs

  lazy val verbIdsByType = instances.get.map(
    _.map { case (verbType, sentences) =>
      verbType -> sentences.toList.foldMap { case (sid, verbs) =>
        verbs.value.keySet.map(vi => VerbId(sid, vi)).toSet
      }
    }
  ).toCell("Verb IDs")

  private[this] def getVerbIdToTypeMap[A](instances: Instances[VerbType, A]) = {
    instances.toList.foldMap { case (verbType, sentences) =>
      sentences.toList.foldMap { case (sentenceId, verbs) =>
        NonMergingMap(
          verbs.value.toList.map { case (verbIndex, _) =>
            VerbId(sentenceId, verbIndex) -> verbType
          }.toMap
        )
      }
    }
  }

  lazy val verbIdToType = instances.get
    .map(getVerbIdToTypeMap)
    .toCell("Verb ID to verb type mapping")

  lazy val instancesByVerbId = instances.get.map(
    _.map { case (verbType, sentences) =>
      verbType -> sentences.toList.foldMap { case (sid, verbs) =>
        verbs.value.toList.foldMap { case (verbIndex, instanceIds) =>
          NonMergingMap(VerbId(sid, verbIndex) -> instanceIds)
        }
      }
    }
  ).toCell("Instances by verb ID")

  // Verb vectors

  import breeze.linalg.DenseVector

  def getVerbVectors(
    verbIdToType: Map[VerbId, VerbType],
    filePrefix: String
  ): IO[Instances[VerbType, DenseVector[Float]]] = {
    val idsPath = Paths.get(filePrefix + "_ids.jsonl")
    val embPath = Paths.get(filePrefix + "_emb.bin")
    val embDim = 1024
    for {
      ids <- Log.infoBranch("Reading verb IDs")(
        FileUtil.readJsonLines[VerbId](idsPath).compile.toList
      )
      embeddings <- Log.infoBranch("Reading verb embeddings")(
        VectorFileUtil.readDenseFloatVectorsNIO(embPath, embDim)
      )
      _ <- Log.info(s"Number of IDs: ${ids.size}; Number of embeddings: ${embeddings.size}; embedding size: ${embeddings.head.size}")
      _ <- {
        val numToCheck = 5
        val propSane = embeddings.take(numToCheck)
          .foldMap(
            _.activeValuesIterator.map(scala.math.abs).filter(f => f > 1e-2 && f < 1e2).size
          ).toDouble / (numToCheck * embDim)
        val sanityCheckText = f"Sanity check: ${propSane}%.3f of ELMo embedding units have absolute value between ${1e-2}%s and ${1e2}%s."
        if(propSane < 0.8) {
          Log.warn(sanityCheckText) >>
            Log.warn("There might be endianness issues with how you're reading the ELMo embeddings") >>
            embeddings.take(numToCheck).traverse(e => Log.info(e.activeValuesIterator.take(10).mkString("\t")))
        } else Log.info(sanityCheckText)
      }
    } yield ids.zip(embeddings)
      .foldMap { case (vid @ VerbId(sentenceId, verbIndex), embedding) =>
        verbIdToType.get(vid).foldMap(verbType => // don't include vectors for verbs not in the provided instances
          Map(verbType -> Map(sentenceId -> NonMergingMap(verbIndex -> embedding)))
        )
      }
  }

  // ELMo

  lazy val elmoVecs = verbIdToType.get.zip(RunData.strings).flatMap {
    case (vIdToVType, split) =>
      getVerbVectors(
        vIdToVType.value,
        inputDir.resolve(s"elmo/$split").toString
      )
  }.toCell("Verb ELMo vectors")

  // XXXXXXXXX

  // just for clauses
  // def makeVerbSpecificClauseVocab(instances: Map[String, Map[Int, QAPairs]]): Vocab[ArgStructure] = {
  //   Vocab.make(
  //     instances.values.toList.foldMap(verbMap =>
  //       verbMap.values.toList.foldMap(qMap =>
  //         qMap.keys.toList.map { case (frame, slot) =>
  //           ArgStructure(frame.args, frame.isPassive).forgetAnimacy
  //         }.toSet
  //       )
  //     )
  //   )
  // }

  // def readFramesets(vsConfig: VerbSenseConfig)(implicit cs: ContextShift[IO]) = {
  //   framesetsPath(vsConfig).flatMap(path =>
  //     FileUtil.readJsonLines[VerbFrameset](path)
  //       .compile.toList
  //       .map(_.map(f => f.inflectedForms -> f).toMap)
  //   )
  // }
  // def writeFramesets(
  //   vsConfig: VerbSenseConfig,
  //   framesets: Map[InflectedForms, VerbFrameset])(
  //   implicit cs: ContextShift[IO]
  // ): IO[Unit] = framesetsPath(vsConfig).flatMap(path =>
  //   FileUtil.writeJsonLines(path)(
  //     framesets.values.toList
  //   )
  // )
}

class GoldQasrlFeatures(
  mode: RunMode)(
  implicit cs: ContextShift[IO],
  Log: EphemeralTreeLogger[IO, String]
) extends Features[InflectedForms, QuestionId](mode)(implicitly[Encoder[InflectedForms]], implicitly[Decoder[InflectedForms]], cs, Log) {

  override val rootDir = Paths.get("frame-induction/qasrl")

  val qasrlBankPath = Paths.get("../qasrl-bank/data/qasrl-v2_1")

  private[this] def readQasrlDataset(name: String) =
    Log.infoBranch(s"Reading QA-SRL dataset $name")(
      readDataset(qasrlBankPath.resolve(name + ".jsonl.gz"))
    )

  implicit val datasetMonoid = Dataset.datasetMonoid(Dataset.printMergeErrors)

  val qasrlBank = new Cell(
    "QA-SRL Bank",
    Log.infoBranch("Reading QA-SRL Bank")(
      IO(Data.readFromQasrlBank(qasrlBankPath).toEither.right.get)
    )
  )

  val dataset: RunDataCell[Dataset] = RunData(
    train = "expanded/train",
    dev = "expanded/dev",
    test = "orig/test").flatMap(
    spec => readQasrlDataset(spec).map(filterDatasetNonDense)
  ).toCell("QA-SRL dataset")

  def getQAPairs(dataset: Dataset): Instances[InflectedForms, QAPairs] = {
    dataset.sentences
      .iterator.flatMap { case (sid, sentence) => sentence.verbEntries.values.map(sid -> _) }.toList
      .groupBy(_._2.verbInflectedForms).map { case (verbInflectedForms, pairs) =>
        verbInflectedForms -> pairs.groupBy(_._1).map { case (sid, pairs) =>
          sid -> NonMergingMap(
            pairs.map(_._2).map(v => v.verbIndex -> v).toMap.map { case (verbIndex, verb) =>
              val qLabels = verb.questionLabels.values.toList
              val resolvedQs = ClauseResolution.getResolvedFramePairs(
                verbInflectedForms, qLabels.map(_.questionSlots)
              ).map(Function.tupled(ClausalQuestion(_, _)))
              verbIndex -> qLabels.zip(resolvedQs).map { case (qLabel, clausalQ) =>
                clausalQ -> (
                  qLabel.answerJudgments.toList.flatMap(_.judgment.getAnswer).map(_.spans.toList)
                )
              }.toMap
            }
          )
        }
      }
  }

  val qidToSpans: RunDataCell[Map[QuestionId, List[List[ESpan]]]] = dataset.get
    .map(getQAPairs)
    .map(
      _.toList.foldMap { case (_, sentences) =>
        sentences.toList.foldMap { case (sid, verbs) =>
          verbs.value.toList.foldMap { case (verbIndex, qaPairs) =>
            qaPairs.map { case (cq, spanLists) =>
              QuestionId(VerbId(sid, verbIndex), cq) -> spanLists
            }
          }
        }
      }
    ).toCell("QA-SRL QuestionId to spans")

  override val instances: RunDataCell[Instances[InflectedForms, Set[QuestionId]]] =
    dataset.get
      .map(getQAPairs)
      .map(
        _.transform { case (_, sentences) =>
          sentences.transform { case (sid, verbs) =>
            NonMergingMap(
              verbs.value.transform { case (verbIndex, qaPairs) =>
                qaPairs.keySet.map { cq =>
                  QuestionId(VerbId(sid, verbIndex), cq)
                }
              }
            )
          }
        }
      ).toCell("QA-SRL QuestionId instances")

  override val sentences = dataset.get
    .map(_.sentences.map { case (sid, sent) => sid -> sent.sentenceTokens })
    .map(NonMergingMap.apply[String, Vector[String]])
    .toCell("QA-SRL sentences")

  // lazy val instancesByQuestionId = instances.get.map(
  //   _.map { case (verbType, sentences) =>
  //     verbType -> sentences.toList.foldMap { case (sid, verbs) =>
  //       verbs.value.toList.foldMap { case (vi, questions) =>
  //         val verbId = VerbId(sid, vi)
  //         questions.map { case (question, spanLists) =>
  //           QuestionId(verbId, question) -> spanLists
  //         }.toMap
  //       }
  //     }
  //   }
  // ).toCell("Instances by question ID")


  // lazy val questionTemplateVocabsCachePath = cacheDir.map(_.resolve("question-vocabs.jsonl.gz"))
  // lazy val questionTemplateVocabsByVerb = new Cell(
  //   "Question template vocabularies",
  //   questionTemplateVocabsCachePath >>= (path =>
  //     FileCached.get[Map[InflectedForms, Vocab[QuestionTemplate]]]("Question template vocabularies")(
  //       path = path,
  //       read = path => (
  //         FileUtil.readJsonLines[(InflectedForms, Vocab[QuestionTemplate])](path)
  //           .infoCompile("Reading lines")(_.toList).map(_.toMap)
  //       ),
  //       write = (path, clausalQVocabs) => FileUtil.writeJsonLines(
  //         path, io.circe.Printer.noSpaces)(
  //         clausalQVocabs.toList))(
  //       instances.all.get >>= (
  //         _.toList.infoBarTraverse("Constructing clausal question vocabularies") {
  //           case (verbType, sentences) =>
  //             Log.trace(verbType.toString) >> IO {
  //               val qTemplateSet = sentences.unorderedFoldMap(verbs =>
  //                 verbs.value.unorderedFoldMap(qaPairs =>
  //                   qaPairs.keySet.map(QuestionTemplate.fromClausalQuestion)
  //                 )
  //               )
  //               verbType -> Vocab.make(qTemplateSet)
  //             }
  //         }.map(_.toMap)
  //       )
  //     )
  //   )
  // )

  // // inputs sent to a QA model
  // // def qaInputPath = outDir.map(_.resolve(s"qa-input-${mode.eval}.jsonl.gz"))

  // // outputs of a QA model, read back in to construct features
  // def qaOutputPath(split: String) =
  //   IO.pure(inputDir.resolve("qa"))
  //     .flatTap(createDir)
  //     .map(_.resolve(s"qa-output-$split.jsonl.gz"))
  // def qaFeaturesPath(split: String) =
  //   cacheDir.map(_.resolve("qa"))
  //     .flatTap(createDir)
  //     .map(_.resolve(s"qa-features-$split.jsonl.gz"))

  // lazy val answerNLLs = RunData.splits.flatMap { split =>
  //   import QAInputApp.{SentenceQAOutput, ClauseQAOutput}
  //   for {
  //     insts <- instances(split)
  //     vidToType <- verbIdToType(split)
  //     splitName <- RunData.strings(split)
  //     qaOutPath <- qaOutputPath(splitName)
  //     res <- FileUtil.readJsonLines[SentenceQAOutput](
  //       qaOutPath
  //     ).map { case SentenceQAOutput(sid, verbs) =>
  //       verbs.toList.foldMap { case (verbIndexStr, clausalQAs) =>
  //         val verbIndex = verbIndexStr.toInt
  //         val verbId = VerbId(sid, verbIndex)
  //         val verbTypeOpt = vidToType.value.get(verbId)
  //         verbTypeOpt.foldMap { verbType =>
  //           val verbInstances = insts(verbType)(sid).value(verbIndex)
  //           Map(
  //             verbType -> verbInstances.map { case (cq, spans) =>
  //               val goldSpans = spans.flatten.toSet
  //               QuestionId(verbId, cq) -> clausalQAs.map {
  //                 case ClauseQAOutput(question, spans) =>
  //                   val marginalAnswerProb = spans
  //                     .filter(p => goldSpans.contains(p._1))
  //                     .foldMap(_._2)
  //                   // smoothed prob chosen here bc it's 1/2 of the bottom decoding threshold for the QA model
  //                   val smoothedMarginalAnswerProb =
  //                     scala.math.max(marginalAnswerProb, 0.0025)
  //                   question.clausalQ -> -scala.math.log(smoothedMarginalAnswerProb)
  //               }
  //             }
  //           )
  //         }
  //       }
  //     }.infoCompile("Compiling QA NLL features from sentence predictions")(_.foldMonoid)
  //   } yield res
  // }.toFileCachedCell("QA NLL features", qaFeaturesPath)(
  //   read = path => (
  //     FileUtil.readJsonLines[(InflectedForms, List[(QuestionId, List[(TemplateQ, Double)])])](path)
  //       .map { case (k, v) => k -> v.toMap }
  //       .infoCompile("Reading lines")(_.toList)
  //       .map(_.toMap)
  //   ),
  //   write = (path, qaFeatures) => FileUtil.writeJsonLines(
  //     path, io.circe.Printer.noSpaces)(
  //     qaFeatures.toList.map { case (k, v) => k -> v.toList })
  // )


  val liveDir = IO.pure(rootDir.resolve("live")).flatTap(createDir)

  // TODO refactor into RunData framework
  val evaluationItemsPath = liveDir.map(_.resolve(s"eval-sample-${mode.eval}.jsonl"))
  val evaluationItems = {
      new Cell(
        "Evaluation items",
        evaluationItemsPath >>= (evalItemsPath =>
          FileCached.get[Vector[(InflectedForms, String, Int)]](
            "Evaluation Items")(
            path = evalItemsPath,
            read = path => FileUtil.readJsonLines[(InflectedForms, String, Int)](path).compile.toVector,
            write = (path, items) => FileUtil.writeJsonLines(path)(items))(
            Log.infoBranch(s"Creating new sample for evaluation at $evalItemsPath")(
              dataset.eval.get.map { evalSet =>
                (new scala.util.Random(86735932569L)).shuffle(
                  evalSet.sentences.values.iterator.flatMap(sentence =>
                    sentence.verbEntries.values.toList.map(verb =>
                      (verb.verbInflectedForms, sentence.sentenceId, verb.verbIndex)
                    )
                  )
                ).take(1000).toVector
              }
            )
          )
        )
    )
  }

  val paraphraseGoldPath = liveDir.map(_.resolve("gold-paraphrases.json"))

  def readGoldParaphrases = {
    paraphraseGoldPath >>= (ppGoldPath =>
      IO(Files.exists(ppGoldPath)).ifM(
        FileUtil.readJson[ParaphraseAnnotations](ppGoldPath),
        Log.warn(s"No gold paraphrase annotations found at $ppGoldPath. Initializing to empty annotations.") >>
          IO.pure(Map.empty[String, Map[Int, VerbParaphraseLabels]]),
        )
    )
  }
  def saveGoldParaphrases(data: ParaphraseAnnotations) = {
    paraphraseGoldPath >>= (ppGoldPath =>
      Log.infoBranch(s"Saving gold paraphrases to $ppGoldPath")(
        FileUtil.writeJson(ppGoldPath, io.circe.Printer.noSpaces)(data)
      )
    )
  }
}

import qfirst.ontonotes._

import cats.Order

// import io.circe.generic.JsonCodec

import monocle.macros.Lenses

@Lenses @JsonCodec case class ArgumentId(
  verbId: VerbId, span: ESpan
)
object ArgumentId {
  implicit val argumentIdOrder =
    Order.whenEqual(
      Order.by[ArgumentId, VerbId](_.verbId),
      Order.by[ArgumentId, ESpan](_.span)
    )
}

case class PropBankRoleLabel(
  framesetId: String,
  role: String
)

// verb type is either lemma or sense, depending on assumeGoldVerbSense value (false/true resp.).
class PropBankGoldSpanFeatures(
  mode: RunMode,
  assumeGoldVerbSense: Boolean)(
  implicit cs: ContextShift[IO],
  Log: EphemeralTreeLogger[IO, String]
) extends Features[String, ArgumentId](mode)(implicitly[Encoder[String]], implicitly[Decoder[String]], cs, Log) {

  override val rootDir = Paths.get("frame-induction/propbank")

  val ontonotesPath = Paths.get("data/conll-formatted-ontonotes-5.0")

  val ontonotesService = new CoNLLFileSystemService(ontonotesPath)

  val fullIndex = Cell("OntoNotes Index")(
    Log.infoBranch("Reading OntoNotes file paths")(
      ontonotesService.getAllPaths
    )
  )

  val index: RunDataCell[List[CoNLLPath]] = RunData(
    train = "train",
    dev = "development",
    test = "test").flatMap(
    spec => fullIndex.get.map(_.filter(_.split == spec))
  ).toCell("OntoNotes Index")

  val dataset: RunDataCell[Instances[String, (String, Map[ESpan, String])]] = index.get.flatMap(filePaths =>
    filePaths.infoBarFoldMapM("Reading PropBank files to construct instances") { path =>
      Log.trace(path.suffix) >> ontonotesService.getFile(path) >>= { file =>
        file.sentences.traceBarFoldMapM("Sentences") { sentence =>
          val sentenceId = sentence.path.toString
          sentence.predicateArgumentStructures.foldMap { pas =>
            val verbLemma = pas.predicate.lemma
            val verbSense = s"$verbLemma.${pas.predicate.sense}"
            val verbType = if(assumeGoldVerbSense) verbSense else verbLemma
            IO.pure(
              Map(
                verbType -> Map(
                  sentenceId -> NonMergingMap(
                    pas.predicate.index ->
                      (verbSense -> pas.arguments.map(_.swap).toMap)
                  )
                )
              )
            )
          }
        }
      }
    }
  ).toCell("PropBank dataset")
  //   (filePaths: List[CoNLLPath]): IO[] = {

  override val instances = dataset.get.map(
    _.transform { case (_, sentences) =>
      sentences.transform { case (sid, verbs) =>
        NonMergingMap(
          verbs.value.transform { case (verbIndex, (_, labels)) =>
            labels.keySet.map(span =>
              ArgumentId(VerbId(sid, verbIndex), span)
            )
          }
        )
      }
    }
  ).toCell("PropBank gold span instances")

  val spanIdToRoleLabel = dataset.get.map(
    _.toList.foldMap { case (_, sentences) =>
      sentences.toList.foldMap { case (sid, verbs) =>
        verbs.value.toList.foldMap { case (verbIndex, (framesetId, arguments)) =>
          NonMergingMap(
            arguments.map { case (span, label) =>
              ArgumentId(VerbId(sid, verbIndex), span) -> PropBankRoleLabel(framesetId, label)
            }
          )
        }
      }
    }.value
  ).toCell("PropBank span to role label mapping")

  override val sentences: RunDataCell[NonMergingMap[String, Vector[String]]] =
    index.get.flatMap(
      _.infoBarFoldMapM("Constructing sentence index") { path =>
        ontonotesService.getFile(path).map(
          _.sentences.foldMap(s => NonMergingMap(s.path.toString -> s.tokens.map(_.token).toVector))
        )
      }
    ).toCell("Sentence index")

  @JsonCodec case class PropBankQGInput(
    sentenceId: String,
    sentenceTokens: Vector[String],
    verbEntries: Map[Int, PropBankQGVerbInput]
  )
  @JsonCodec case class PropBankQGVerbInput(
    argumentSpans: Set[ESpan]
  )

  val qgInputs = index.get.map { paths =>
    Stream.emits[IO, CoNLLPath](paths) >>= { path =>
      Stream.eval(ontonotesService.getFile(path)) >>= { file =>
        Stream.emits[IO, PropBankQGInput](
          file.sentences.map { sentence =>
            PropBankQGInput(
              sentence.path.toString,
              sentence.tokens.map(_.token).toVector,
              sentence.predicateArgumentStructures.map { pas =>
                pas.predicate.index -> PropBankQGVerbInput(pas.arguments.map(_._2).toSet)
              }.toMap
            )
          }
        )
      }
    }
  }

  def getQGInputOutPath(split: String) = outDir
    .map(_.resolve(s"qg-inputs")).flatTap(createDir)
    .map(_.resolve(s"$split.jsonl.gz"))

  val writeQGInputs = RunData.strings.zip(qgInputs).flatMap { case (split, inputStream) =>
    getQGInputOutPath(split) >>= (outPath =>
      IO(Files.exists(outPath)).ifM(
        Log.info(s"QG Inputs already found at $outPath."),
        Log.infoBranch(s"Logging QG inputs to $outPath")(
          FileUtil.writeJsonLinesStreaming(outPath, io.circe.Printer.noSpaces)(inputStream)
        )
      )
    )
  }.run
}

// class PropBankFeatures(
//   implicit mode: RunMode,
//   cs: ContextShift[IO],
//   Log: EphemeralTreeLogger[IO, String]
// ) extends Features[String] {
//   type QABeam = List[SimpleQAs.BeamItem[SlotBasedLabel[VerbForm]]]

//   def getPropBankPredictedInstances(
//     predictions: Stream[IO, PropBankSentencePrediction[QABeam]],
//     filter: SimpleQAs.Filter
//   ): IO[Instances.PropBank] = {
//     val protocol = SimpleQAs.protocol[SlotBasedLabel[VerbForm]](useMaxQuestionDecoding = false)
//     predictions.map { sentencePred => // remove "be" since out of scope of QA-SRL
//       sentencePred.verbs.filter(v => v.verbLemma != "be" && v.beam.nonEmpty).foldMap(
//         verbPred => Map(
//           verbPred.verbLemma -> Map(
//             sentencePred.sentenceId -> Map(
//               verbPred.verbIndex ->
//                 protocol.filterBeam(filter, verbPred.toGenericVerbPrediction).map {
//                   case (qString, (slots, spans)) => slots -> List(spans.toList)
//                 }
//             ).filter(_._2.nonEmpty)
//           ).filter(_._2.nonEmpty)
//         ).filter(_._2.nonEmpty)
//       )
//     }.infoCompile("Sentences")(_.foldMonoid).map(Instances(_))
//   }

//   def getPropBankSenseLabels(
//     predictions: Stream[IO, PropBankSentencePrediction[QABeam]]
//   ): IO[Instances.PropBankLabels] = {
//     val resIO = predictions.map { sentencePred =>
//       sentencePred.verbs.filter(_.beam.nonEmpty).foldMap(
//         verbPred => Map(
//           verbPred.verbLemma -> Map(
//             sentencePred.sentenceId -> Map(
//               verbPred.verbIndex -> Vector(verbPred.verbSense)
//             )
//           )
//         )
//       )
//     }.compile.foldMonoid
//     resIO.map(
//       _.transform { case (_, verbTypeMap) =>
//         verbTypeMap.transform { case (_, sentenceMap) =>
//           sentenceMap.transform { case (_, verbSenseVec) =>
//             assert(verbSenseVec.size == 1)
//             verbSenseVec.head
//           }
//         }
//       }
//     ).map(Instances(_))
//   }


//   val propBankPredictionsPath = inputDir.resolve("qasrl-predictions")

//   val propBankQasrlFilter = {
//     import io.circe.generic.auto._
//     new Cell(
//       "PropBank QA-SRL Filter",
//       FileUtil.readJson[SimpleQAs.Filter](
//         propBankPredictionsPath.resolve(s"filter.json")
//       )
//     )
//   }

//   def readPropBankInstances(name: String) = {
//     import io.circe.generic.auto._
//     Log.infoBranch(s"Reading QA-SRL on PropBank $name set")(
//       propBankQasrlFilter.get.flatMap(filter =>
//         for {
//           bad <- Ref[IO].of(0)
//           total <- Ref[IO].of(0)
//           res <- getPropBankPredictedInstances(
//             FileUtil.readJsonLines[PropBankSentencePrediction[QABeam]](
//               propBankPredictionsPath.resolve(s"propbank-$name-qasrl.jsonl.gz")
//             ).flatMap { x =>
//               import io.circe.syntax._
//               if(x.verbs.exists(_.beam.isEmpty)) {
//                 Stream.eval_(bad.update(_ + 1) >> total.update(_ + 1))
//               } else Stream.eval(total.update(_ + 1).as(x))
//             }, filter
//           )
//           _ <- (bad.get, total.get).mapN((b, t) =>
//             Log.info(s"Ignored $b/$t sentences due to apparently bad verbs.")
//           ).flatten
//         } yield res
//       )
//     )
//   }

//   override val instances = RunData.strings
//     .flatMap(readPropBankInstances)
//     .toCell("PropBank instances")

//   def readPropBankLabels(name: String) = {
//     import io.circe.generic.auto._
//     Log.infoBranch(s"Reading verb sense labels on PropBank $name set")(
//       getPropBankSenseLabels(
//         FileUtil.readJsonLines[PropBankSentencePrediction[QABeam]](
//           propBankPredictionsPath.resolve(s"propbank-$name-qasrl.jsonl.gz")
//         )
//       )
//     )
//   }

//   override val labels = RunData.strings
//     .flatMap(readPropBankLabels)
//     .toCell("PropBank labels")
// }

// class PredictedQasrlFeatures(
//   implicit mode: RunMode,
//   cs: ContextShift[IO],
//   Log: EphemeralTreeLogger[IO, String]
// ) extends Features[InflectedForms] {
//   // def getPredictedInstances(
//   //   predictions: Stream[IO, SentencePrediction[QABeam]],
//   //   filter: SimpleQAs.Filter
//   // ): IO[Instances] = {
//   //   val protocol = SimpleQAs.protocol[SlotBasedLabel[VerbForm]](useMaxQuestionDecoding = false)
//   //   predictions.map { sentencePred =>
//   //     sentencePred.verbs.foldMap(
//   //       verbPred => Map(
//   //         verbPred.verbInflectedForms -> Map(
//   //           sentencePred.sentenceId -> Map(
//   //             verbPred.verbIndex ->
//   //               protocol.filterBeam(filter, verbPred).map {
//   //                 case (qString, (slots, spans)) => slots -> spans
//   //               }
//   //           )
//   //         )
//   //       )
//   //     )
//   //   }.compile.foldMonoid
//   // }

// }

