"""LoRA/QLoRA fine-tuning for PidginGPT.  STATUS: written, NOT yet executed (needs GPU).
Usage: python ai-training/training/train_lora.py --config ai-training/training/configs/lora.yaml
Install: pip install -e "ai-training[train]"
"""
import argparse, json, os, shutil, sys
import yaml


def load_chat_jsonl(path, tok, max_len):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            ex = json.loads(line)
            text = tok.apply_chat_template(ex["messages"], tokenize=False)
            ids = tok(text, truncation=True, max_length=max_len)["input_ids"]
            rows.append({"input_ids": ids, "labels": list(ids), "attention_mask": [1] * len(ids)})
    return rows


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--config", required=True); a = ap.parse_args()
    cfg = yaml.safe_load(open(a.config))
    if not cfg.get("base_model_revision"):
        sys.exit("Refusing to train: set base_model_revision (pin the exact model commit) for reproducibility.")
    import torch
    from transformers import (AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, Trainer,
                              TrainingArguments, DataCollatorForSeq2Seq, set_seed)
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
    set_seed(cfg["seed"]); os.makedirs(cfg["output_dir"], exist_ok=True)
    shutil.copy(a.config, os.path.join(cfg["output_dir"], "config.snapshot.yaml"))

    tok = AutoTokenizer.from_pretrained(cfg["base_model"], revision=cfg["base_model_revision"])
    if tok.pad_token is None: tok.pad_token = tok.eos_token
    q = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16) if cfg["load_in_4bit"] else None
    model = AutoModelForCausalLM.from_pretrained(cfg["base_model"], revision=cfg["base_model_revision"],
                                                 quantization_config=q, torch_dtype=torch.bfloat16)
    if q: model = prepare_model_for_kbit_training(model)
    l = cfg["lora"]
    model = get_peft_model(model, LoraConfig(r=l["r"], lora_alpha=l["alpha"], lora_dropout=l["dropout"],
                                             target_modules=l["target_modules"], task_type="CAUSAL_LM"))
    model.print_trainable_parameters()
    train = load_chat_jsonl(cfg["train_file"], tok, cfg["max_seq_len"])
    val = load_chat_jsonl(cfg["validation_file"], tok, cfg["max_seq_len"])
    args = TrainingArguments(output_dir=cfg["output_dir"], num_train_epochs=cfg["epochs"],
        per_device_train_batch_size=cfg["per_device_batch_size"], gradient_accumulation_steps=cfg["grad_accum_steps"],
        learning_rate=float(cfg["learning_rate"]), warmup_ratio=cfg["warmup_ratio"], lr_scheduler_type=cfg["lr_scheduler"],
        bf16=cfg["bf16"], logging_steps=cfg["logging_steps"], save_steps=cfg["save_steps"], eval_steps=cfg["eval_steps"],
        eval_strategy="steps", save_total_limit=3, report_to=[], seed=cfg["seed"])
    Trainer(model=model, args=args, train_dataset=train, eval_dataset=val,
            data_collator=DataCollatorForSeq2Seq(tok, padding=True)).train()
    model.save_pretrained(os.path.join(cfg["output_dir"], "lora-adapter"))
    print("Saved adapter. Next: ai-training/export/merge_and_quantize.sh")


if __name__ == "__main__":
    main()
