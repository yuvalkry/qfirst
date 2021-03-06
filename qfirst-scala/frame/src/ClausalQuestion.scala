package qfirst.frame

import qfirst.clause.ArgStructure

import qasrl.ArgumentSlot
import qasrl.Frame

import cats.Order
import cats.implicits._

import io.circe.generic.JsonCodec

import monocle.macros.Lenses

@Lenses @JsonCodec case class ClausalQuestion(
  frame: Frame,
  slot: ArgumentSlot
) {
  def questionString = frame.questionsForSlot(slot).head
  def clauseTemplate = ArgStructure(frame.args, frame.isPassive).forgetAnimacy
  def template: TemplateQ = clauseTemplate -> slot
  // TODO remove need for this
  def tuple: (Frame, ArgumentSlot) = (frame, slot)
}
object ClausalQuestion {
  implicit val clausalQuestionOrder: Order[ClausalQuestion] = Order.whenEqual(
    Order.by[ClausalQuestion, String](_.frame.toString),
    Order.by[ClausalQuestion, String](_.slot.toString)
  )
}
