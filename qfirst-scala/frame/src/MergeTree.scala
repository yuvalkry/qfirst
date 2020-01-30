package qfirst.frame

import cats.Applicative
import cats.kernel.CommutativeMonoid
import cats.Monad
import cats.Eval
import cats.Foldable
import cats.Reducible
import cats.UnorderedFoldable
import cats.implicits._

import monocle.macros._

import io.circe.generic.JsonCodec

import scala.annotation.tailrec

/*@JsonCodec */sealed trait MergeTree[A] {
  def rank: Int
  def loss: Double
  def values: Vector[A]
  def valuesWithLosses: Vector[(Double, A)]

  import MergeTree._

  def map[B](f: A => B): MergeTree[B] = this match {
    case Leaf(l, a) => Leaf(l, f(a))
    case Merge(r, l, left, right) => Merge(r, l, left.map(f), right.map(f))
  }

  // TODO fix ranks
  def cutMap[B](
    shouldKeep: Merge[A] => Boolean,
    f: MergeTree[A] => B
  ): MergeTree[B] = this match {
    case l @ Leaf(loss, _) => Leaf(loss, f(l))
    case m @ Merge(rank, loss, left, right) =>
      if(shouldKeep(m)) {
        Merge(rank, loss, left.cutMap(shouldKeep, f), right.cutMap(shouldKeep, f))
      } else Leaf(loss, f(m))
  }

  def cut(shouldKeep: Merge[A] => Boolean): MergeTree[MergeTree[A]] =
    cutMap(shouldKeep, identity)

  def cutMapAtN[B](n: Int, f: MergeTree[A] => B) = cutMap(
    _.rank > (rank + 1 - n), f
  )

  def splitByPredicate(shouldSplit: Merge[A] => Boolean): Vector[MergeTree[A]] = this match {
    case l @ Leaf(_, _) => Vector(l)
    case m @ Merge(r, p, left, right) =>
      if(shouldSplit(m)) {
        left.splitByPredicate(shouldSplit) ++ right.splitByPredicate(shouldSplit)
      } else Vector(m)
  }

  def splitToN(n: Int): Vector[MergeTree[A]] = {
    assert(n >= 1)
    splitByPredicate(_.rank > (rank + 1 - n))
  }

  def isLeaf: Boolean = this match {
    case Leaf(_, _) => true
    case _ => false
  }
  def isMerge: Boolean = this match {
    case Merge(_, _, _, _) => true
    case _ => false
  }

  // returns Some if value appears at most once in the merge tree
  def clustersForValue(value: A): Option[List[MergeTree[A]]] = this match {
    case Leaf(_, `value`) => Some(List(this)) // 0 because thresholding doesn't affect leaves
    case Merge(_, loss, left, right) =>
      left.clustersForValue(value)
        .orElse(right.clustersForValue(value))
        .map(this :: _)
    case _ => None
  }

  def clusterSplittings = MergeTree.clusterSplittings(Vector(this))

  def cata[B](
    leaf: (Double, A) => B,
    merge: (Int, Double, B, B) => B
  ): B = MergeTree.cata(MergeTree.makeAlgebra(leaf, merge))(this)

  def depth = cata[Int](
    leaf = (_, _) => 0,
    merge = (_, _, l, r) => scala.math.max(l, r) + 1
  )

  def toJsonStringSafe(implicit e: io.circe.Encoder[A]) = {
    import io.circe.syntax._
    cata[Vector[String]](
      leaf = (loss, value) => Vector(Leaf(loss, value).asJson.noSpaces),
      merge = (rank, loss, left, right) => Vector(
        Vector("{\"rank\": " + rank.asJson.noSpaces + ", \"loss\": " + loss.asJson.noSpaces + ", \"left\": "),
        left,
        Vector(", \"right\": "),
        right,
        Vector("}")
      ).flatten
    ).mkString
  }
}
object MergeTree {
  // def reduceLossThreshold[A](trees: Vector[MergeTree[A]]): Vector[MergeTree[A]] = {
  //   val maxLoss = trees.map(_.loss).max
  //   trees.flatMap(_.splitByPredicate(_.loss >= maxLoss)) -> maxLoss
  // }
  def clusterSplittings[A](trees: Vector[MergeTree[A]]) = {
    trees #:: clusterSplittingsAux(trees, trees.map(_.loss).max)
  }
  private[this] def clusterSplittingsAux[A](trees: Vector[MergeTree[A]], maxLoss: Double): Stream[Vector[MergeTree[A]]] = {
    if(trees.forall(_.isLeaf)) Stream.empty[Vector[MergeTree[A]]] else {
      val newTrees = trees.flatMap(_.splitByPredicate(_.loss >= maxLoss))
      val newMaxLoss = newTrees.filter(_.isMerge).map(_.loss).max
      newTrees #:: clusterSplittingsAux(newTrees, newMaxLoss)
    }
  }

  type Algebra[B, A] = Either[(Double, B), (Int, Double, A, A)] => A
  type AlgebraM[F[_], B, A] = Either[(Double, B), (Int, Double, A, A)] => F[A]
  def makeAlgebra[B, A](
    leaf: (Double, B) => A,
    merge: (Int, Double, A, A) => A
  ): Algebra[B, A] = {
    (params: Either[(Double, B), (Int, Double, A, A)]) => params match {
      case Left((loss, value)) => leaf(loss, value)
      case Right((rank, loss, left, right)) => merge(rank, loss, left, right)
    }
  }
  type Coalgebra[B, A] = A => Either[(Double, B), (Int, Double, A, A)]
  type CoalgebraM[F[_], B, A] = A => F[Either[(Double, B), (Int, Double, A, A)]]

  def embed[A]: Algebra[A, MergeTree[A]] = makeAlgebra[A, MergeTree[A]](
    Leaf(_, _), Merge(_, _, _, _)
  )
  def embedM[F[_]: Applicative, A]: AlgebraM[F, A, MergeTree[A]] =
    embed[A] andThen Applicative[F].pure[MergeTree[A]]

  def project[A]: Coalgebra[A, MergeTree[A]] = (tree: MergeTree[A]) => tree match {
    case Leaf(loss, value) => Left(loss -> value)
    case Merge(rank, loss, left, right) => Right((rank, loss, left, right))
  }
  def projectM[F[_]: Applicative, A]: CoalgebraM[F, A, MergeTree[A]] = {
    project[A] andThen Applicative[F].pure[Either[(Double, A), (Int, Double, MergeTree[A], MergeTree[A])]]
  }

  import cats.Monad

  // not inherently stack-safe; must be used with a stack-safe monad
  def hyloM[F[_]: Monad, A, B, C](
    destructM: CoalgebraM[F, B, A],
    constructM: AlgebraM[F, B, C]
  ): A => F[C] = (a: A) => {
    def loop( // TODO all below
      toVisit: List[A],
      toCollect: List[Either[(Int, Double), F[C]]]
    ): F[C] = toCollect match {
      case Right(rightM) :: Right(leftM) :: Left((rank, loss)) :: tail =>
        // eagerly merge to keep the stack size small
        val merged = for {
          left <- leftM
          right <- rightM
          merge <- constructM(Right((rank, loss, left, right)))
        } yield merge
        loop(toVisit, Right(merged) :: tail)
      case _ => toVisit match {
        case a :: next => destructM(a).flatMap {
          case Left((loss, value)) =>
            loop(next, Right(constructM(Left(loss -> value))) :: toCollect)
          case Right((rank, loss, left, right)) =>
            loop(left :: right :: next, Left(rank -> loss) :: toCollect)
        }
        case Nil => toCollect.head.right.get // should always work
      }
    }
    loop(List(a), Nil)
  }
  def anaM[F[_]: Monad, A, B](destruct: CoalgebraM[F, B, A]): A => F[MergeTree[B]] = {
    hyloM(destruct, embedM[F, B])
  }
  def cataM[F[_]: Monad, A, B](construct: AlgebraM[F, A, B]): MergeTree[A] => F[B] = {
    hyloM(projectM[F, A], construct)
  }

  // stack-safe backbone for tree transformations
  def hylo[A, B, C](
    destruct: Coalgebra[B, A],
    construct: Algebra[B, C]
  ): A => C = (a: A) => {
    @tailrec
    def loop(
      toVisit: List[A],
      toCollect: List[Either[(Int, Double), C]]
    ): C = toCollect match {
      case Right(right) :: Right(left) :: Left((rank, loss)) :: tail =>
        // eagerly merge to keep the stack size small
        loop(toVisit, Right(construct(Right((rank, loss, left, right)))) :: tail)
      case _ => toVisit match {
        case a :: next => destruct(a) match {
          case Left((loss, value)) =>
            loop(next, Right(construct(Left(loss -> value))) :: toCollect)
          case Right((rank, loss, left, right)) =>
            loop(left :: right :: next, Left(rank -> loss) :: toCollect)
        }
        case Nil => toCollect.head.right.get
      }
    }
    loop(List(a), Nil)
  }
  def ana[A, B](destruct: Coalgebra[B, A]): A => MergeTree[B] = {
    hylo(destruct, embed[B])
  }
  def cata[A, B](construct: Algebra[A, B]): MergeTree[A] => B = {
    hylo(project[A], construct)
  }

  // stack-safe implementation of hylo particular to the Either monad (for json decoding)
  def hyloEither[E, A, B, C](
    destructM: CoalgebraM[Either[E, ?], B, A],
    constructM: AlgebraM[Either[E, ?], B, C]
  ): A => Either[E, C] = (a: A) => {
    @tailrec
    def loop(
      toVisit: List[A],
      toCollect: List[Either[(Int, Double), C]]
    ): Either[E, C] = toCollect match {
      case Right(right) :: Right(left) :: Left((rank, loss)) :: tail =>
        // eagerly merge to keep the stack size small
        constructM(Right((rank, loss, left, right))) match {
          case Right(merged) => loop(toVisit, Right(merged) :: tail)
          case Left(error) => Left(error)
        }
      case _ => toVisit match {
        case a :: next => destructM(a) match {
          case Left(error) => Left(error)
          case Right(Left((loss, value))) =>
            constructM(Left(loss -> value)) match {
              case Left(error) => Left(error)
              case Right(leaf) => loop(next, Right(leaf) :: toCollect)
            }
          case Right(Right((rank, loss, left, right))) =>
            loop(left :: right :: next, Left(rank -> loss) :: toCollect)
        }
        case Nil => Right(toCollect.head.right.get) // should always work
      }
    }
    loop(List(a), Nil)
  }
  def anaEither[E, A, B](destruct: CoalgebraM[Either[E, ?], B, A]): A => Either[E, MergeTree[B]] = {
    hyloEither(destruct, embedM[Either[E, ?], B])
  }
  def cataEither[E, A, B](construct: AlgebraM[Either[E, ?], A, B]): MergeTree[A] => Either[E, B] = {
    hyloEither(projectM[Either[E, ?], A], construct)
  }

  // def tailRecM[B](arg: A)(func: A => Tree[Either[A, B]]): Tree[B] = {
  //   @tailrec
  //   def loop(toVisit: List[Tree[Either[A, B]]],
  //            toCollect: List[Option[Tree[B]]]): List[Tree[B]] =
  //     toVisit match {
  //       case Branch(l, r) :: next =>
  //         loop(l :: r :: next, None :: toCollect)

  //       case Leaf(Left(value)) :: next =>
  //         loop(func(value) :: next, toCollect)

  //       case Leaf(Right(value)) :: next =>
  //         loop(next, Some(pure(value)) :: toCollect)

  //       case Nil =>
  //         toCollect.foldLeft(Nil: List[Tree[B]]) { (acc, maybeTree) =>
  //           maybeTree.map(_ :: acc).getOrElse {
  //             val left :: right :: tail = acc
  //             branch(left, right) :: tail
  //           }
  //         }
  //     }

  //   loop(List(func(arg)), Nil).head
  // }

  @Lenses @JsonCodec case class Leaf[A](
    loss: Double, value: A) extends MergeTree[A] {
    def rank: Int = 0
    def values: Vector[A] = Vector(value)
    def valuesWithLosses: Vector[(Double, A)] = Vector(loss -> value)
  }
  object Leaf
  @Lenses case class Merge[A](
    rank: Int,
    loss: Double,
    left: MergeTree[A],
    right: MergeTree[A]
  ) extends MergeTree[A] {
    def values: Vector[A] = left.values ++ right.values
    def valuesWithLosses: Vector[(Double, A)] = left.valuesWithLosses ++ right.valuesWithLosses
  }
  object Merge

  import io.circe._
  import io.circe.syntax._

  implicit def mergeTreeEncoder[A: Encoder]: Encoder[MergeTree[A]] =
    Encoder.instance[MergeTree[A]](
      _.cata[Json](
        leaf = (loss, value) => Json.obj(
          "loss" -> loss.asJson, "value" -> value.asJson
        ),
        merge = (rank, loss, left, right) => Json.obj(
          "rank" -> rank.asJson, "loss" -> loss.asJson, "left" -> left, "right" -> right
        )
      ))
  implicit def mergeTreeDecoder[A: Decoder]: Decoder[MergeTree[A]] = {
    Decoder.instance[MergeTree[A]](
      anaEither[DecodingFailure, ACursor, A](c =>
        if(c.downField("rank").succeeded) {
          for {
            rank <- c.downField("rank").as[Int]
            loss <- c.downField("loss").as[Double]
          } yield Right((rank, loss, c.downField("left"), c.downField("right")))
        } else {
          for {
            loss <- c.downField("loss").as[Double]
            value <- c.downField("value").as[A]
          } yield Left((loss, value))
        }
      )
    )
  }

  // TODO add if needed
  // def leaf[P, A] = GenPrism[MergeTree[P, A], Leaf[P, A]]
  // def merge[P, A] = GenPrism[MergeTree[P, A], Merge[P, A]]
  // def param[P1, P2, A] = PLens[MergeTree[P1, A], MergeTree[P2, A], P1, P2](
  //   _.param)(p2 => mt =>
  //   mt match {
  //     case Leaf(_, value) => Leaf(p2, value)
  //     case Merge(rank, _, left, right) => Merge(rank, p2, left, right)
  //   }
  // )

  implicit val mergeTreeFoldable: UnorderedFoldable[MergeTree] = {
    new UnorderedFoldable[MergeTree] {
      type F[A] = MergeTree[A]

      // def reduceLeftTo[A, B](fa: F[A])(f: A => B)(g: (B, A) => B): B = {
      //   fa match {
      //     case Leaf(_, value) => f(value)
      //     case Merge(_, _, left, right) =>
      //       foldLeft(right, reduceLeftTo(left)(f)(g))(g)
      //   }
      // }

      // // TODO make this properly lazy or... ?? not sure if this works exactly as I expect
      // def reduceRightTo[A, B](fa: F[A])(f: A => B)(g: (A, Eval[B]) => Eval[B]): Eval[B] = {
      //   fa match {
      //     case Leaf(_, value) => Eval.later(f(value))
      //     case Merge(_, _, left, right) =>
      //       foldRight(left, reduceRightTo(right)(f)(g))(g)
      //   }
      // }

      def unorderedFoldMap[A, B: CommutativeMonoid](fa: F[A])(f: A => B): B = fa.cata[B](
        leaf = (_, value) => f(value),
        merge = (_, _, left, right) => left |+| right
      )

      // def foldLeft[A, B](fa: F[A], b: B)(f: (B, A) => B): B = fa match {
      //   case Leaf(_, value) => f(b, value)
      //   case Merge(_, _, left, right) =>
      //     foldLeft(right, foldLeft(left, b)(f))(f)
      // }

      // def foldRight[A, B](fa: F[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
      //   fa match {
      //     case Leaf(_, value) => f(value, lb)
      //     case Merge(_, _, left, right) =>
      //       foldRight(left, foldRight(right, lb)(f))(f)
      //   }
      // }
    }
  }
}
