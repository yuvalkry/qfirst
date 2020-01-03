package freelog

import cats._
import cats.implicits._
import cats.effect.IO
import cats.effect.ExitCode
import cats.effect.Sync
import cats.effect.concurrent.Ref

import com.monovore.decline._
import com.monovore.decline.effect._

import scala.concurrent.duration._

object Main extends CommandIOApp(
  name = "mill freelog.run",
  header = "FreeLog sandbox."){

  implicit class RichTraverse[F[_]: Traverse, A](fa: F[A]) {
    def traverseLogging[G[_]: Monad, B](
      getMessage: Int => String)(
      f: A => G[B])(
      implicit logger: EphemeralLogger[String, G]) = {
      // val prefix = if(label.isEmpty) "" else s"$label: "
      logger.block {
        logger.replace(getMessage(0)) >>
          fa.traverseWithIndexM { (a, index) =>
            f(a) <* logger.replace(getMessage(index + 1))
          }
      }
    }

    def traverseLoggingIter[G[_]: Monad, B](
      prefix: String)(
      f: A => G[B])(
      implicit logger: EphemeralLogger[String, G]) = {
      traverseLogging(i => s"${prefix}${i}it")(f)
    }

    def getProgressBar(length: Int)(total: Long)(cur: Long): String = {
      val num = math.round(cur.toDouble / total * length).toInt
      val pct = math.round(cur.toDouble / total * 100.0).toInt
      val bars = "#" * num
      val spaces = " " * (length - num)
      f"$pct%3d%% [$bars%s$spaces%s] $cur%d/$total%d"
    }

    def traverseLoggingProgress[G[_]: Monad, B](
      prefix: String, size: Long = fa.size)(
      f: A => G[B])(
      implicit logger: EphemeralLogger[String, G]) = {
      val progress = getProgressBar(30)(size) _
      traverseLogging(i => s"${prefix}${progress(i)}")(f)
    }

    // def traverseLoggingIterSync[G[_]: Monad : Sync, B](
    //   label: String)(
    //   f: A => G[B])(
    //   implicit logger: EphemeralLogger[String, G]) = {
    //   val prefix = if(label.isEmpty) "" else s"$label: "
    //   Ref[G].of(0) >>= { iter =>
    //     logger.block {
    //       fa.traverse { a =>
    //         iter.get >>= (curIter =>
    //           logger.rewind >> logger.log(s"${prefix}${curIter}it") >>
    //             f(a) >>
    //             iter.update(_ + 1)
    //         )
    //       }
    //     }
    //   }
    // }

    // def traverseLoggingIterSync[G[_]: Monad : Sync, B](
    //   label: String)(
    //   f: A => G[B])(
    //   implicit logger: EphemeralLogger[String, G]) = {
    //   val prefix = if(label.isEmpty) "" else s"$label: "
    //   Ref[G].of(0) >>= { iter =>
    //     logger.block {
    //       fa.traverse { a =>
    //         iter.get >>= (curIter =>
    //           logger.rewind >> logger.log(s"${prefix}${curIter}it") >>
    //             f(a) >>
    //             iter.update(_ + 1)
    //         )
    //       }
    //     }
    //   }
    // }

    // private[this] def makeProgressBar // TODO

    // def traverseLoggingProgressSync[G[_]: Monad : Sync, B](
    //   label: String, size: Long = fa.size)(
    //   f: A => G[B])(
    //   implicit logger: EphemeralLogger[String, G]) = {
    //   val prefix = if(label.isEmpty) "" else s"$label: "
    //   Ref[G].of(0) >>= { iter =>
    //     logger.block {
    //       fa.traverse { a =>
    //         iter.get >>= (curIter =>
    //           logger.rewind >> logger.log(s"${prefix}${curIter}it") >>
    //             f(a) >>
    //             iter.update(_ + 1)
    //         )
    //       }
    //     }
    //   }
    // }
  }

  def program: IO[ExitCode] = {
    for {
      implicit0(logger: EphemeralTreeLogger[String, IO]) <- freelog.loggers.EphemeralTreeConsoleLogger.create()
      _ <- (1 to 1000).toList.traverseLoggingIter("Initializing: ")(_ => IO.sleep(0.002.seconds))

      _ <- (1 to 1000).toList.traverseLoggingProgress("Buffering: ")(_ => IO.sleep(0.002.seconds))
      _ <- logger.branch("Recruiting") {
        (1 to 1000).toList.traverseLoggingProgress("New recruits: ")(_ => IO.sleep(0.002.seconds))
      }
      _ <- logger.branch("Dominating") {
        logger.log("Trying soft power..") >> IO.sleep(0.5.seconds) >>
          logger.log("Acknowledging failure of soft power...") >> IO.sleep(1.second) >>
          logger.replace("Mobilizing army...") >> IO.sleep(1.second) >>
          logger.branch("Invading...") {
            logger.log("Landfall!") >> IO.sleep(0.5.seconds) >>
              (1 to 100).toList.traverseLoggingProgress("Enemies crushed: ")(
                _ => IO.sleep(0.01.seconds)
              ) >> IO.sleep(0.5.seconds) >>
              logger.log("Victory!") >> IO.sleep(1.seconds)
          } >> IO.sleep(0.5.seconds) >>
          logger.log("Now what?")
      }
    } yield ExitCode.Success
  }

  val main: Opts[IO[ExitCode]] = {
    Opts.unit.as(program)
  }
}
