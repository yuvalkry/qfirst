package qfirst.datasets.propbank3

import qfirst.datasets.PredArgStructure
import qfirst.datasets.PropBankPredicate
import qfirst.datasets.propbank1.PropBankArgument

case class PropBank3Sentence(
  id: PropBank3SentenceId,
  predArgStructures: List[PredArgStructure[PropBankPredicate, PropBankArgument]]
)

import jjm.ling.ESpan

case class PropBank3SentenceCoNLLStyle(
  id: PropBank3SentenceId,
  predArgStructures: List[PredArgStructure[PropBankPredicate, ESpan]]
)
