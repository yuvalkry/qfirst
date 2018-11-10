import codecs
import os
import logging
from typing import Dict, List, Optional, Tuple, Set
from collections import Counter
import json
import gzip

from overrides import overrides

from allennlp.common import Params
from allennlp.common.file_utils import cached_path
from allennlp.data.dataset_readers.dataset_reader import DatasetReader
from allennlp.data.instance import Instance
from allennlp.data.token_indexers import SingleIdTokenIndexer, TokenIndexer
from allennlp.data.fields import Field, IndexField, TextField, SequenceLabelField, LabelField, ListField, MetadataField, SpanField
from allennlp.data.tokenizers import Token
from allennlp.data.dataset_readers.dataset_utils.span_utils import enumerate_spans

from nrl.common.span import Span
from nrl.data.util import cleanse_sentence_text

from qfirst.data.util import get_slot_label_namespace

logger = logging.getLogger(__name__)  # pylint: disable=invalid-name

qasrlv2_0_prepositions = [
    "to", "by", "with", "to do", "in", "for", "from", "as", "on",
    "of", "into", "up", "through", "about", "out", "at", "", "down", "off",
    "doing", "against", "over", "do", "out of", "around", "after", "between", "by doing", "to as",
    "up of", "upon", "within", "from doing", "under", "on to", "for doing", "up to", "without", "up in",
    "across", "down into", "during", "behind", "along", "until", "toward", "in doing", "onto", "before",
    "on to do", "as doing", "above", "among", "up doing", "of doing", "out to", "down on", "on doing", "towards",
    "up by", "inside", "with doing", "alongside", "after doing", "out by", "near", "ahead", "below", "up for",
    "down by", "throughout", "up on", "off by", "beneath", "off from", "down from", "aside", "beyond", "down to",
    "up into", "up from", "before doing", "in for", "out from", "off of", "through doing", "of as", "about doing", "at doing",
    "out to do", "out for", "out on", "over by", "on by", "off for", "off into", "since", "in on", "ahead of",
    "than", "on in", "inside of", "in from", "behind by", "down at", "up at", "amid", "over to", "over as",
    "via", "along by", "up to do", "off as", "down through", "until doing", "down in", "down doing", "without doing", "amongst",
    "up as", "aside for", "from inside", "in to", "out in", "during doing", "along in", "out doing", "out of doing", "through to",
    "against doing", "ahead by", "in as", "despite", "underneath", "beside", "next to", "as to", "out onto", "outside",
    "amid doing", "until after", "out over", "up in doing", "at about", "out during", "up around",
    "given", "about below", "by without",
    "by before", "out about", "from around", "up against", "on from", "given up", "outside of", "about into", "off in", "up after",
    "on as", "on into", "over into", "on over", "out by doing", "around to", "out below", "next", "behind doing", "over from",
    "around doing", "up through", "down to do", "off doing", "opposite", "round up", "towards doing", "down for", "in to do", "along doing",
    "toward doing", "through to do", "given to", "to on", "between doing", "among doing", "about by", "to as doing", "to by", "up about",
    "to in", "except", "upon by", "down as", "up from doing", "off to",
]

qasrlv2_0_global_prepositions = [
    "by", "for", "with", "in", "from", "to", "as"
]

def get_qasrlv2_0_allowed_prepositions(tokens: List[str]) -> Set[str]:
    lower_tokens = [t.lower() for t in tokens]
    bigrams = [t[0] + " " + t[1] for t in zip(lower_tokens, lower_tokens[1:])]
    all_candidates = lower_tokens.concat(bigrams)
    all_contained_simple_preps = [s for s in all_candidates if s in qasrlv2_0_prepositions]
    all_simple_preps = qasrlv2_0_global_prepositions.concat(all_contained_simple_preps)
    all_possible_preps = [prep for p in all_simple_preps
                          for prep in [p, p + " do", p + " doing"]
                          if prep in qasrlv2_0_prepositions].concat(["_", "", "do", "doing"])
    set(all_possible_preps)

def read_clause_info(target, file_path):
    def get(targ, key):
        if key not in targ:
            targ[key] = {}
        return targ[key]
    def add_json(json):
        sentence = get(target, json["sentenceId"])
        verb = get(sentence, json["verbIndex"])
        clause = get(verb, json["question"])
        slot_dict = json["slots"]
        slot_dict["qarg"] = json["answerSlot"]
        clause["slots"] = slot_dict

    if file_path.endswith('.gz'):
        with gzip.open(file_path, 'r') as f:
            for line in f:
                add_json(json.loads(line))
    elif file_path.endswith(".jsonl"):
        with codecs.open(file_path, 'r', encoding='utf8') as f:
            for line in f:
                add_json(json.loads(line))
    return

qasrl_slot_names = ["wh", "aux", "subj", "verb", "obj", "prep", "obj2"]

def get_token_list_from_slot(slot_value: str) -> List[str]:
    if slot_value == "_":
        return []
    elif " " in slot_value:
        return slot_value.split(" ")
    else:
        return [slot_value]

def get_question_tokens(question_slots):
    return [
        token
        for slot_name in qasrl_slot_names
        for token in get_token_list_from_slot(question_slots[slot_name])
    ]

def get_question_slot_fields(question_slots):
    """
    Input: json dicty thing from QA-SRL Bank
    Output: dict from question slots to fields
    """
    def get_slot_value_field(slot_name):
        slot_value = question_slots[slot_name]
        namespace = get_slot_label_namespace(slot_name)
        return LabelField(label = slot_value, label_namespace = namespace)
    return { slot_name : get_slot_value_field(slot_name) for slot_name in qasrl_slot_names }


def get_answer_spans_field(label, text_field):
    def get_spans(spansJson):
        return [SpanField(s[0], s[1]-1, text_field) for s in spansJson]
    span_list = [s for ans in label["answerJudgments"] if ans["isValid"] for s in get_spans(ans["spans"]) ]
    if len(span_list) == 0:
        return ListField([SpanField(-1, -1, text_field)])
    else:
        return ListField(span_list)

def get_num_answers_field(question_label):
    return LabelField(label = len(question_label["answerJudgments"]), skip_indexing = True)

def get_num_invalids_field(question_label):
    num_invalids = len(question_label["answerJudgments"]) - len([aj for aj in question_label["answerJudgments"] if aj["isValid"]])
    return LabelField(label = num_invalids, skip_indexing = True)

@DatasetReader.register("qfirst_qasrl")
class QasrlReader(DatasetReader):
    def __init__(self,
                 instance_type: str,
                 token_indexers: Dict[str, TokenIndexer] = None,
                 min_answers = 1,
                 min_valid_answers = 0,
                 question_sources = None,
                 domains = None,
                 include_abstract_slots: bool = False,
                 clause_info_files: List[str] = []):
        super().__init__(False)
        self._token_indexers = token_indexers or {"tokens": SingleIdTokenIndexer(lowercase_tokens=True)}
        self._min_answers = min_answers
        self._min_valid_answers = min_valid_answers
        logger.info("Reading QA-SRL questions with at least %s answers and %s valid answers." % (self._min_answers, self._min_valid_answers))
        self._slot_names = ["wh", "aux", "subj", "verb", "obj", "prep", "obj2"]
        # self._grammar_fields = ["tense", "isPerfect", "isProgressive", "isNegated", "isPassive"]
        self._abstract_slots = [
            ("wh", lambda x: "what" if (x == "who") else x),
            ("subj", lambda x: "something" if (x == "someone") else x),
            ("obj", lambda x: "something" if (x == "someone") else x),
            ("obj2", lambda x: "something" if (x == "someone") else x),
            ("prep", lambda x: "_" if (x == "_") else "<prep>")
        ]
        # also an abstract verb slot marking whether something is passive

        self._instance_type = instance_type

        self._num_instances = 0
        self._num_verbs = 0

        self._question_sources = question_sources
        self._domains = [d.lower() for d in domains] if domains is not None else None

        self._include_abstract_slots = include_abstract_slots

        self._clause_info = None
        if len(clause_info_files) > 0:
            self._clause_info = {}
            for file_path in clause_info_files:
                read_clause_info(self._clause_info, file_path)

    @overrides
    def _read(self, file_list: str):
        instances = []
        for file_path in file_list.split(","):
            if file_path.strip() == "":
                continue

            logger.info("Reading QASRL instances from dataset file at: %s", file_path)
            data = []
            if file_path.endswith('.gz'):
                with gzip.open(cached_path(file_path), 'r') as f:
                    for line in f:
                        data.append(json.loads(line))
            elif file_path.endswith(".jsonl"):
                with codecs.open(cached_path(file_path), 'r', encoding='utf8') as f:
                    for line in f:
                        data.append(json.loads(line))

            for item in data:
                sentence_id = item["sentenceId"]
                sentence_tokens = item["sentenceTokens"]
                is_sentence_in_domain = self._domains is None or any([d in sentence_id.lower() for d in self._domains])

                if is_sentence_in_domain:
                    for _, verb_entry in item["verbEntries"].items():
                        verb_index = verb_entry["verbIndex"]
                        verb_inflected_forms = verb_entry["verbInflectedForms"]

                        def is_valid(question_label):
                            answers = question_label["answerJudgments"]
                            valid_answers = [a for a in answers if a["isValid"]]
                            is_source_valid = self._question_sources is None or any([l.startswith(source) for source in self._question_sources for l in question_label["questionSources"]])
                            return (len(answers) >= self._min_answers) and (len(valid_answers) >= self._min_valid_answers) and is_source_valid
                        question_labels = [l for q, l in verb_entry["questionLabels"].items() if is_valid(l)]

                        self._num_verbs += 1
                        if self._instance_type == "verb":
                            if len(question_labels) != 0:
                                self._num_instances += 1
                                yield self._make_gold_verb_instance(sentence_id, sentence_tokens, verb_index, verb_inflected_forms, question_labels)
                        elif self._instance_type == "question":
                            for l in question_labels:
                                self._num_instances += 1
                                next_instance = self._make_gold_question_instance(sentence_id, sentence_tokens, verb_index, l)
                                if next_instance is not None:
                                    yield next_instance


        logger.info("Produced %d instances"%self._num_instances)
        logger.info("\t%d Verbs"%self._num_verbs)

    def _make_gold_verb_instance(self, #type: ignore
                            sentence_id: str,
                            sent_tokens: List[str],
                            pred_index: int,
                            verb_inflected_forms: Dict[str, str],
                            question_labels): # List[Json]
        """
        Returns
        -------
        An ``Instance`` containing the following fields:
            text : ``TextField`` (length n)
                The tokens in the sentence.
            predicate_index : ``IndexField`` over text
            predicate_indicator : ``SequenceLabelField`` over text
                Sequence of 0 or 1 with a single 1 marking the predicate.
            slot_X : ``ListField[LabelField]``
                Slot X value for questions involving the predicate.
                There is one of these for each slot X, each having its own label space.
            answers : ``ListField[ListField[SpanField]]`` (spans over text)
                Answer spans for each question, in order.
                Collapses together spans provided by different annotators into a single list.
                Retains repeats.
            num_invalids: List[int]
                Number of annotator judgments that a question was invalid.
            metadata : ``Metadatafield`` containing:
                text : sentence tokens joined by spaces
                pred_index : index of the predicate in the token list
                sentence_id : the sentence ID string.
                question_sources : the IDs of the question sources, in order.
                question_labels : the question labels.
        """
        instance_dict = {}

        if isinstance(sent_tokens, str):
            sent_tokens = sent_tokens.split()
        sent_tokens = cleanse_sentence_text(sent_tokens)
        text_field = TextField([Token(t) for t in sent_tokens], self._token_indexers)
        predicate_index_field = IndexField(pred_index, text_field)
        predicate_indicator_field = SequenceLabelField([1 if i == pred_index else 0 for i in range(len(sent_tokens))], text_field)

        slot_dicts = [get_question_slot_fields(l["questionSlots"]) for l in question_labels]
        slots_dict = { slot_name : ListField([slot_dict[slot_name] for slot_dict in slot_dicts]) for slot_name in qasrl_slot_names }

        answer_fields_field = ListField([get_answer_spans_field(l, text_field) for l in question_labels])
        num_answers_field = ListField([get_num_answers_field(l) for l in question_labels])
        num_invalids_field = ListField([get_num_invalids_field(l) for l in question_labels])

        metadata = {
            'pred_index' : pred_index,
            'sent_text': " ".join(sent_tokens),
            "verb_inflected_forms": verb_inflected_forms
        }
        metadata['sentence_id'] = sentence_id
        metadata['sentence_tokens'] = sent_tokens
        metadata['question_sources'] = [l["questionSources"] for l in question_labels]
        metadata['question_labels'] = question_labels

        instance_dict = {
            'text': text_field,
            'predicate_index': predicate_index_field,
            'predicate_indicator': predicate_indicator_field,
            'answers': answer_fields_field,
            'num_answers': num_answers_field,
            'num_invalids': num_invalids_field,
            'metadata': MetadataField(metadata),
        }

        return Instance({**instance_dict, **slots_dict})

    def _make_gold_question_instance(self, #type: ignore
                            sentence_id: str,
                            sent_tokens: List[str],
                            pred_index: int,
                            question_label): # Json
        """
        Returns
        -------
        An ``Instance`` containing the following fields:
            text : ``TextField`` (length n)
                The tokens in the sentence.
            predicate_indicator : ``SequenceLabelField`` over text
                Sequence of 0 or 1 with a single 1 marking the predicate.
            slot_X : ``LabelField``
                Slot X value for questions involving the predicate.
                There is one of these for each slot X, each having its own label space.
            answers : ``ListField[SpanField]`` (spans over text)
                Answer spans for the question.
                Collapses together spans provided by different annotators into a single list.
                Retains repeats.
            num_invalids: int
                Number of annotator judgments that a question was invalid.
            metadata : ``Metadatafield`` containing:
                text : sentence tokens joined by spaces
                pred_index : index of the predicate in the token list
                sentence_id : the sentence ID string.
                question_sources : the IDs of the question sources, in order.
                question_label : the question label.
        """
        instance_dict = {}

        if isinstance(sent_tokens, str):
            sent_tokens = sent_tokens.split()
        sent_tokens = cleanse_sentence_text(sent_tokens)
        text_field = TextField([Token(t) for t in sent_tokens], self._token_indexers)
        predicate_index_field = IndexField(pred_index, text_field)
        predicate_indicator_field = SequenceLabelField([1 if i == pred_index else 0 for i in range(len(sent_tokens))], text_field)

        slots = question_label["questionSlots"]
        slots_dict = get_question_slot_fields(slots)
        question_text_field = TextField([Token(t) for t in get_question_tokens(question_label["questionSlots"])], self._token_indexers)

        # TODO factor out into separate method maybe
        def get_abstract_slot_value_field(slot_name, get_abstracted_value):
            abst_slot_name = "abst-%s" % slot_name
            namespace = get_slot_label_namespace(abst_slot_name)
            abst_slot_value = get_abstracted_value(slots[slot_name])
            return LabelField(label = abst_slot_value, label_namespace = namespace)
        direct_abstract_slots_dict = { ("abst-%s" % slot_name): get_abstract_slot_value_field(slot_name, get_abstracted_value)
                                       for slot_name, get_abstracted_value in self._abstract_slots }
        abst_verb_value = "verb[pss]" if question_label["isPassive"] else "verb"
        abst_verb_field = LabelField(label = abst_verb_value, label_namespace = get_slot_label_namespace("abst-verb"))
        abstract_slots_dict = {**direct_abstract_slots_dict, **{"abst-verb": abst_verb_field}}

        def get_clause_slot_field(slot_name, slot_value):
            clause_slot_name = "clause-%s" % slot_name
            namespace = get_slot_label_namespace(clause_slot_name)
            return LabelField(label = slot_value, label_namespace = namespace)
        clause_slots_dict = {}
        if self._clause_info is not None:
            try:
                clause_dict = self._clause_info[sentence_id][pred_index][question_label["questionString"]]
                clause_slots_dict = { ("clause-%s" % k) : get_clause_slot_field(k, v) for k, v in clause_dict["slots"].items() }
            except KeyError:
                logger.info("Omitting instance without clause data: %s / %s / %s" % (sentence_id, pred_index, question_label["questionString"]))
                return None

        answers_field = get_answer_spans_field(question_label, text_field)
        num_answers_field = get_num_answers_field(question_label)
        num_invalids_field = get_num_invalids_field(question_label)

        metadata = {'pred_index' : pred_index, 'sent_text': " ".join(sent_tokens)}
        metadata['sentence_id'] = sentence_id
        metadata['question_sources'] = question_label["questionSources"]
        metadata['question_label'] = question_label

        instance_dict = {
            'text': text_field,
            'predicate_index': predicate_index_field,
            'predicate_indicator': predicate_indicator_field,
            'question_text': question_text_field,
            'answer_spans': answers_field,
            'num_answers': num_answers_field,
            'num_invalids': num_invalids_field,
            'metadata': MetadataField(metadata),
        }

        if self._include_abstract_slots:
            return Instance({**instance_dict, **slots_dict, **abstract_slots_dict, **clause_slots_dict})
        else:
            return Instance({**instance_dict, **slots_dict, **clause_slots_dict })

    @classmethod
    def from_params(cls, params: Params) -> 'QasrlReader':
        token_indexers = TokenIndexer.dict_from_params(params.pop('token_indexers', Params({"tokens": {"type": "single_id", "lowercase_tokens": True}})))
        instance_type = params.pop("instance_type")

        # has_provenance = params.pop("has_provenance", False)

        min_answers = params.pop("min_answers", 1)
        min_valid_answers = params.pop("min_valid_answers", 0)

        question_sources = params.pop("question_sources", None)
        domains = params.pop("domains", None)

        clause_info_files = params.pop("clause_info_files", [])

        params.assert_empty(cls.__name__)
        return QasrlReader(token_indexers = token_indexers, instance_type = instance_type,
                           min_answers = min_answers, min_valid_answers = min_valid_answers,
                           question_sources = question_sources,
                           domains = domains,
                           clause_info_files = clause_info_files)
