package qfirst.frame.models

import qfirst.frame.MergeTree

import cats.Foldable
import cats.data.NonEmptyList
import cats.implicits._

import scala.util.Random

import io.circe.generic.JsonCodec

import scala.collection.immutable.Vector

class CompleteLinkageClustering(
  distances: Array[Array[Double]] // fuzzy eq neg log probs
) extends AgglomerativeClusteringAlgorithm {
  type ClusterParam = Set[Int] // DenseVector[Double] // boolean sets
  type Instance = Int // DenseVector[Double] // fuzzy eq neg log probs
  // case class Hyperparams(vocabSize: Int)

  // loss of a cluster is max distance between any two elements
  def getInstanceLoss(
    instance: Instance,
    param: ClusterParam,
  ): Double = {
    param.map(i => distances(i)(instance)).max
  }

  def getSingleInstanceParameter(
    index: Int,
    instance: Instance
  ): ClusterParam = {
    Set(instance)
  }

  override def mergeParams(
    instances: Vector[Instance],
    left: MergeTree[Int],
    leftParam: ClusterParam,
    right: MergeTree[Int],
    rightParam: ClusterParam
  ): ClusterParam = {
    leftParam union rightParam
  }

  // only need to consider pairs that cross between clusters to find the new max pairwise distance
  override def mergeLoss(
    instances: Vector[Instance],
    left: MergeTree[Int],
    leftParam: ClusterParam,
    right: MergeTree[Int],
    rightParam: ClusterParam
  ): Double = {
    val leftValues = left.values
    val rightValues = right.values
    val param = (leftValues ++ rightValues).toSet
    val newLoss = leftParam.iterator.flatMap { lv =>
      rightParam.iterator.map { rv =>
        distances(lv)(rv)
      }
    }.max
    val loss = List(newLoss, left.loss, right.loss).max
    if(!(newLoss >= left.loss && newLoss >= right.loss)) {
      println("WARNING: clusters seem to be incorrectly merged")
      println(left)
      println(right)
      println(newLoss)
      ???
    }
    loss
  }

  override def getLossChangePriority(
    newLoss: Double,
    leftLoss: Double,
    rightLoss: Double
  ) = List(newLoss, leftLoss, rightLoss).max // though newLoss should always be biggest?

}
