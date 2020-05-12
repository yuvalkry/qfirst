package qfirst.frame.models

import qfirst.frame.MergeTree

import cats.data.NonEmptyVector
import cats.implicits._

trait CompositeAgglomerativeClusteringAlgorithm extends AgglomerativeClusteringAlgorithm {
  val _1: AgglomerativeClusteringAlgorithm
  val _1Lambda: Double
  val _2: AgglomerativeClusteringAlgorithm { type Index = _1.Index }
  val _2Lambda: Double
  type ClusterParam = (_1.ClusterParam, _2.ClusterParam)
  type Index = _1.Index

  def getSingleInstanceParameter(
    index: Index
  ): ClusterParam = {
    _1.getSingleInstanceParameter(index) ->
      _2.getSingleInstanceParameter(index)
  }

  def getInstanceLoss(
    index: Index,
    param: ClusterParam
  ): Double = {
    (_1Lambda * _1.getInstanceLoss(index, param._1)) +
      (_2Lambda * _2.getInstanceLoss(index, param._2))
  }

  def mergeParams(
    left: MergeTree[Index],
    leftParam: ClusterParam,
    right: MergeTree[Index],
    rightParam: ClusterParam
  ): ClusterParam = {
    _1.mergeParams(left, leftParam._1, right, rightParam._1) ->
      _2.mergeParams(left, leftParam._2, right, rightParam._2)
  }

  def mergeLoss(
    left: MergeTree[Index],
    leftParam: ClusterParam,
    right: MergeTree[Index],
    rightParam: ClusterParam
  ): Double = {
    (_1Lambda * _1.mergeLoss(left, leftParam._1, right, rightParam._1)) +
      (_2Lambda * _2.mergeLoss(left, leftParam._2, right, rightParam._2))
  }
}