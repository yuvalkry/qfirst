{
    "dataset_reader": {
        "type": "qfirst_qasrl",
        "token_indexers": {
            "elmo": {
                "type": "elmo_characters"
            }
        },
        "qasrl_filter": {
            "min_answers": 1,
            "min_valid_answers": 0
        },
        "instance_reader": {
            "type": "question_factored",
            "clause_info_files": ["clause-data-train-dev.jsonl"]
        },
        "include_metadata": true
    },
    "train_data_path": "dev-mini.jsonl",
    "validation_data_path": "dev-mini.jsonl",
    // "test_data_path": "dev-mini.jsonl",
    // "train_data_path": "qasrl-v2_1/expanded/train.jsonl.gz",
    // "validation_data_path": "qasrl-v2_1/expanded/dev.jsonl.gz",
    // "test_data_path": "qasrl-v2_1/orig/test.jsonl.gz",
    "model": {
        "type": "e2e_qasrl",
        "text_field_embedder": {
            "elmo": {
                "type": "elmo_token_embedder",
                "options_file": "https://s3-us-west-2.amazonaws.com/allennlp/models/elmo/2x4096_512_2048cnn_2xhighway/elmo_2x4096_512_2048cnn_2xhighway_options.json",
                "weight_file": "https://s3-us-west-2.amazonaws.com/allennlp/models/elmo/2x4096_512_2048cnn_2xhighway/elmo_2x4096_512_2048cnn_2xhighway_weights.hdf5",
                "do_layer_norm": false,
                "dropout": 0.5
            }
        },
        "stacked_encoder": {
            "type": "alternating_lstm",
            "use_highway": true,
            "input_size": 1152,
            "hidden_size": 300,
            "num_layers": 8,
            "recurrent_dropout_probability": 0.1
        },
        "num_pruned_clauses": 2,
        "final_beam_size": 8
    },
    "iterator": {
        "type": "bucket",
        "sorting_keys": [["text", "num_tokens"]],
        "batch_size": 200
    },
    "trainer": {
        "num_epochs": 200,
        "grad_norm": 1.0,
        "patience": 3,
        "validation_metric": "+f1",
        "cuda_device": -1,
        "optimizer": {
            "type": "adadelta",
            "rho": 0.95
        }
    }
}