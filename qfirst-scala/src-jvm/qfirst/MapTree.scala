package qfirst

import cats.implicits._

sealed trait MapTree[A, B] {
  import MapTree._
  def toStringPretty(
    renderKey: A => String,
    renderValue: B => String,
    numSpacesPerIndent: Int = 2
  )(implicit o: Ordering[A]): String = toStringPrettyAux(renderKey, renderValue, numSpacesPerIndent, 0)
  private def toStringPrettyAux(
    renderKey: A => String,
    renderValue: B => String,
    numSpacesPerIndent: Int,
    indentLevel: Int
  )(implicit o: Ordering[A]): String = this match {
    case Leaf(value) => renderValue(value)
    case Fork(children) =>
      "{\n" + children.toList.sortBy(_._1).map {
        case (key, child) =>
          (" " * (numSpacesPerIndent * (indentLevel + 1))) +
            renderKey(key) + ": " +
            child.toStringPrettyAux(renderKey, renderValue, numSpacesPerIndent, indentLevel + 1)
      }.mkString(",\n") + "\n" + (" " * (numSpacesPerIndent * indentLevel)) + "}"
  }

  def toStringPrettySorted[S : Ordering](
    renderKey: A => String,
    renderValue: B => String,
    sortSpec: SortSpec[A, B, S],
    numSpacesPerIndent: Int = 2
  )(implicit o: Ordering[A]): String = toStringPrettySortedAux(renderKey, renderValue, sortSpec, numSpacesPerIndent, 0)
  private def toStringPrettySortedAux[S : Ordering](
    renderKey: A => String,
    renderValue: B => String,
    sortSpec: SortSpec[A, B, S],
    numSpacesPerIndent: Int,
    indentLevel: Int
  )(implicit o: Ordering[A]): String = this match {
    case Leaf(value) => renderValue(value)
    case Fork(children) =>
      "{\n" + children.toList.sorted(sortSpecOrdering(sortSpec)).map {
        case (key, child) =>
          (" " * (numSpacesPerIndent * (indentLevel + 1))) +
            renderKey(key) + ": " +
            child.toStringPrettySortedAux(renderKey, renderValue, sortSpec, numSpacesPerIndent, indentLevel + 1)
      }.mkString(",\n") + "\n" + (" " * (numSpacesPerIndent * indentLevel)) + "}"
  }

  // handles conflicts by defaulting left
  def merge(other: MapTree[A, B], mergeFn: (B, B) => B): MapTree[A, B] = (this, other) match {
    case (Leaf(x), Leaf(y)) => Leaf(mergeFn(x, y))
    case (Fork(x), Fork(y)) =>
      val allKeys = x.keySet ++ y.keySet
      Fork(
        allKeys.toList.map(key =>
          key -> x.get(key).fold(y(key))(xChild => y.get(key).fold(xChild)(yChild => xChild.merge(yChild, mergeFn)))
        ).toMap
      )
    case (x, _) => x
  }

  // standin for optics until we decide to include them
  def getFork: Option[Fork[A, B]] = this match {
    case f @ Fork(_) => Some(f)
    case _ => None
  }
  def getLeaf: Option[Leaf[A, B]] = this match {
    case l @ Leaf(_) => Some(l)
    case _ => None
  }
}
object MapTree {
  case class Fork[A, B](children: Map[A, MapTree[A, B]]) extends MapTree[A, B]
  case class Leaf[A, B](value: B) extends MapTree[A, B]
  def leaf[A, B](value: B): MapTree[A, B] = Leaf[A, B](value)
  def fork[A, B](children: Map[A, MapTree[A, B]]): MapTree[A, B] = Fork(children)
  def fork[A, B](children: (A, MapTree[A, B])*): MapTree[A, B] = Fork(children.toMap)
  def fromMap[A, B](m: Map[A, B]) = Fork(m.map { case (k, v) => k -> Leaf[A, B](v) })
  def fromPairs[A, B](children: (A, B)*): MapTree[A, B] = fromMap(children.toMap)

  def sortSpecOrdering[K: Ordering, V, S : Ordering](spec: SortSpec[K, V, S]): Ordering[(K, MapTree[K, V])] =
    new Ordering[(K, MapTree[K, V])] {
      def compare(x: (K, MapTree[K, V]), y: (K, MapTree[K, V])): Int = (x, y) match {
        case ((xKey, xTree), (yKey, yTree)) =>
          spec.foldRight(implicitly[Ordering[K]].compare(xKey, yKey)) { (query, backup) =>
            (query.getRepValue(xTree), query.getRepValue(yTree)).mapN(implicitly[Ordering[S]].compare).getOrElse(backup)
          }
      }
    }

  type SortSpec[K, V, S] = List[SortQuery[K, V, S]]
  sealed trait SortQuery[K, V, S] {
    def getRepValue(mapTree: MapTree[K, V]): Option[S]
  }
  object SortQuery {
    case class Value[K, V, S](f: V => S) extends SortQuery[K, V, S] {
      def getRepValue(mapTree: MapTree[K, V]): Option[S] =
        mapTree.getLeaf.map(_.value).map(f)
    }
    case class Key[K, V, S](value: K, rest: SortQuery[K, V, S]) extends SortQuery[K, V, S] {
      def getRepValue(mapTree: MapTree[K, V]): Option[S] =
        mapTree.getFork.flatMap(_.children.get(value)).flatMap(rest.getRepValue)
    }
    case class Agg[K, V, S, S2](agg: List[S2] => S, rest: SortQuery[K, V, S2]) extends SortQuery[K, V, S] {
      def getRepValue(mapTree: MapTree[K, V]): Option[S] =
        mapTree.getFork
          .flatMap(_.children.toList.map(_._2).map(rest.getRepValue).sequence)
          .map(agg)
    }

    def key[K, V, S](value: K)(rest: SortQuery[K, V, S]): SortQuery[K, V, S] = Key(value, rest)

    case class ValueBuilder[K]() {
      def apply[V, S](f: V => S): SortQuery[K, V, S] = Value[K, V, S](f)
    }
    def value[K] = ValueBuilder[K]()

    def agg[K, V, S, S2](agg: List[S2] => S)(rest: SortQuery[K, V, S2]): SortQuery[K, V, S] = Agg(agg, rest)
  }

}
