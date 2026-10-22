#!/usr/bin/env python3
"""Writes a TINY, RANDOMLY-INITIALISED llama-architecture GGUF used ONLY to test mechanics
(load, template, tokenize, generate, UTF-8 streaming, cancel, free). It is NOT a language model, produces
gibberish, and must never be shipped or described as a Pidgin model.  usage: make_mechanics_model.py out.gguf"""
import sys, numpy as np
import gguf

out = sys.argv[1]
rng = np.random.default_rng(0)
n_embd, n_ff, n_head, n_layer, ctx = 64, 128, 4, 2, 512
words = ["▁the", "▁na", "▁dey", "▁wetin", "▁abeg", "▁how", "▁body", "▁guy", "▁you", "▁no", "▁wahala", "▁user", "▁assistant", "▁system", "▁:", "▁\n"]
tokens = ["<unk>", "<s>", "</s>"] + [f"<0x{i:02X}>" for i in range(256)] + words + [chr(c) for c in range(97, 123)] + ["▁"]
scores = [0.0] * 3 + [0.0] * 256 + [-1.0] * len(words) + [-5.0] * 26 + [-6.0]
types = [2, 3, 3] + [6] * 256 + [1] * (len(words) + 26 + 1)
n_vocab = len(tokens)

w = gguf.GGUFWriter(out, "llama")
w.add_name("mechanics-test-tiny-random (NOT a language model)")
w.add_context_length(ctx); w.add_embedding_length(n_embd); w.add_block_count(n_layer); w.add_feed_forward_length(n_ff)
w.add_head_count(n_head); w.add_head_count_kv(n_head); w.add_layer_norm_rms_eps(1e-5); w.add_rope_dimension_count(n_embd // n_head)
w.add_file_type(gguf.LlamaFileType.ALL_F32); w.add_vocab_size(n_vocab)
w.add_tokenizer_model("llama"); w.add_token_list(tokens); w.add_token_scores(scores); w.add_token_types(types)
w.add_bos_token_id(1); w.add_eos_token_id(2); w.add_unk_token_id(0); w.add_add_bos_token(True)
w.add_chat_template("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] }}<|im_end|>\n{% endfor %}<|im_start|>assistant\n")
w.add_license("mechanics-test-only")

def t(name, *shape, scale=0.05): w.add_tensor(name, (rng.standard_normal(shape) * scale).astype(np.float32))
def ones(name, n): w.add_tensor(name, np.ones(n, dtype=np.float32))
t("token_embd.weight", n_vocab, n_embd); ones("output_norm.weight", n_embd); t("output.weight", n_vocab, n_embd)
for i in range(n_layer):
    ones(f"blk.{i}.attn_norm.weight", n_embd); ones(f"blk.{i}.ffn_norm.weight", n_embd)
    for n in ("attn_q", "attn_k", "attn_v", "attn_output"): t(f"blk.{i}.{n}.weight", n_embd, n_embd)
    t(f"blk.{i}.ffn_gate.weight", n_ff, n_embd); t(f"blk.{i}.ffn_up.weight", n_ff, n_embd); t(f"blk.{i}.ffn_down.weight", n_embd, n_ff)
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print("wrote", out)
