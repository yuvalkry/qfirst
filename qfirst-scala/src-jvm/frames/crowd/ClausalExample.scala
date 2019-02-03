// package qfirst.frames.crowd
// import qfirst.frames._

// import cats._
// import cats.implicits._

// import qasrl.crowd._
// import qasrl.labeling._
// import qasrl.bank.SentenceId

// import spacro._
// import spacro.tasks._

// import nlpdata.structure.AlignedToken

// // import nlpdata.datasets.wiki1k.Wiki1kFileSystemService
// // import nlpdata.datasets.wiki1k.Wiki1kPath
// // import nlpdata.datasets.wiktionary
// // import nlpdata.datasets.wiktionary.Inflections
// // import nlpdata.datasets.tqa.TQAFileSystemService

// import nlpdata.util.LowerCaseStrings._
// import nlpdata.util.Text
// import nlpdata.util.HasTokens
// import nlpdata.util.HasTokens.ops._

// import akka.actor._
// import akka.stream.scaladsl.Flow
// import akka.stream.scaladsl.Source

// import scala.concurrent.duration._
// import scala.language.postfixOps

// import scala.util.Try

// import upickle.default._

// import java.io.StringReader
// import java.nio.file.{Files, Path, Paths}

// import scala.util.Try
// import scala.util.Random

// import upickle.default._

// class ClausalExample(
//   val label: String = "trial",
//   // qasrlBankPath: Path,
//   clausePredictionPath: Path,
//   frozenEvaluationHITTypeId: Option[String] = None
// )(implicit config: TaskConfig) {

//   val resourcePath = java.nio.file.Paths.get("datasets")

//   import java.nio.file.{Files, Path, Paths}
//   private[this] val liveDataPath = Paths.get(s"data/example/$label/live")
//   implicit val liveAnnotationDataService = new FileSystemAnnotationDataService(liveDataPath)

//   // val staticDataPath = Paths.get(s"data/example/$label/static")

//   // def saveOutputFile(name: String, contents: String): Try[Unit] = Try {
//   //   val path = staticDataPath.resolve("out").resolve(name)
//   //   val directory = path.getParent
//   //   if (!Files.exists(directory)) {
//   //     Files.createDirectories(directory)
//   //   }
//   //   Files.write(path, contents.getBytes())
//   // }

//   // def loadOutputFile(name: String): Try[List[String]] = Try {
//   //   val path = staticDataPath.resolve("out").resolve(name)
//   //   import scala.collection.JavaConverters._
//   //   Files.lines(path).iterator.asScala.toList
//   // }

//   // def loadInputFile(name: String): Try[List[String]] = Try {
//   //   val path = staticDataPath.resolve("in").resolve(name)
//   //   import scala.collection.JavaConverters._
//   //   Files.lines(path).iterator.asScala.toList
//   // }

//   val sentencePredictions = {
//     import ammonite.ops._
//     import io.circe.jawn
//     read.lines(ammonite.ops.Path(clausePredictionPath, pwd)).toList
//       .traverse(jawn.decode[ClausalSentencePrediction])
//       .map(_.map(pred => SentenceId.fromString(pred.sentenceId) -> pred).toMap)
//   }.right.get

//   implicit object SentenceIdHasTokens extends HasTokens[SentenceId] {
//     override def getTokens(id: SentenceId): Vector[String] =
//       sentencePredictions(id).sentenceTokens
//   }

//   val allPrompts = sentencePredictions.keys.toVector.map(ClausalPrompt(_))

//   def numGenerationAssignmentsForPrompt(p: ClausalPrompt[SentenceId]) = 1

//   lazy val experiment = new ClausalAnnotationPipeline[SentenceId](
//     allPrompts,
//     sentencePredictions,
//     numGenerationAssignmentsForPrompt,
//     frozenEvaluationHITTypeId = frozenEvaluationHITTypeId,
//     validationAgreementDisqualTypeLabel = None
//   )

//   // def saveAnnotationData[A](
//   //   filename: String,
//   //   ids: Vector[SentenceId],
//   //   genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
//   //   valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]],
//   //   labelMapper: QuestionLabelMapper[String, A],
//   //   labelRenderer: A => String
//   // ) = {
//   //   saveOutputFile(
//   //     s"$filename.tsv",
//   //     DataIO.makeQAPairTSV(
//   //       ids.toList,
//   //       SentenceId.toString,
//   //       genInfos,
//   //       valInfos,
//   //       labelMapper,
//   //       labelRenderer
//   //     )
//   //   )
//   // }

//   // def saveAnnotationDataReadable(
//   //   filename: String,
//   //   ids: Vector[SentenceId],
//   //   genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
//   //   valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]]
//   // ) = {
//   //   saveOutputFile(
//   //     s"$filename.tsv",
//   //     DataIO.makeReadableQAPairTSV(
//   //       ids.toList,
//   //       SentenceId.toString,
//   //       identity,
//   //       genInfos,
//   //       valInfos,
//   //       (id: SentenceId, qa: VerbQA, responses: List[QASRLValidationAnswer]) =>
//   //         responses.forall(_.isAnswer)
//   //     )
//   //   )
//   // }
// }
