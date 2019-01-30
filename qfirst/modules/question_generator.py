from typing import Dict

import torch

from allennlp.data import Vocabulary
from allennlp.common import Params, Registrable

# abstraction for a model or module which provides
# beam_decode_single, which does beam decoding for a single input verb,
# outputting a list of scored questions (scores not necessarily probabilistic).
class QuestionGenerator(Registrable):
    def __init__(self, vocab: Vocabulary):
        super(QuestionGenerator, self).__init__(vocab)

    def get_slot_names(self):
        raise NotImplementedError()

    def beam_decode_single(self,
                           text: Dict[str, torch.LongTensor],
                           predicate_indicator: torch.LongTensor,
                           max_beam_size: int,
                           min_beam_probability: float):
        raise NotImplementedError()
