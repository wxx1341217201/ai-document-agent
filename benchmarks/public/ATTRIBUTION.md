# Public benchmark data attribution

## CMRC 2018

- Creators: Yiming Cui, Ting Liu, Wanxiang Che, Li Xiao, Zhipeng Chen, Wentao Ma, Shijin Wang, Guoping Hu.
- Original dataset: https://github.com/ymcui/cmrc2018
- Paper: https://aclanthology.org/D19-1600/
- Licence: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0).
- Original licence: https://github.com/ymcui/cmrc2018/blob/master/LICENCE

## HotpotQA

- Creators: Zhilin Yang, Peng Qi, Saizheng Zhang, Yoshua Bengio, William W. Cohen, Ruslan Salakhutdinov, Christopher D. Manning.
- Original dataset: https://hotpotqa.github.io/
- Paper: https://aclanthology.org/D18-1259/
- Dataset licence: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0).
- Downloaded release mirror: https://huggingface.co/datasets/hotpotqa/hotpot_qa (the original CMU download host timed out). The Parquet file is verified against the published SHA-256 recorded in the preparation code and source lock.
- Original code and licence notice: https://github.com/hotpotqa/hotpot
- The upstream evaluation code is Apache-2.0; its licence notice is downloaded separately. This is distinct from the dataset licence.

## Adaptations

The prepared data selects development questions using a fixed seed and content hashes, converts the file format, concatenates HotpotQA context sentences without changing their text, and records original evidence offsets. Original questions, accepted answers and supporting facts are retained. CMRC uses every development context as the retrieval corpus. HotpotQA uses the union of the selected questions' distractor contexts; this is an adapted retrieval setting, not an official fullwiki evaluation.

Derived dataset files in `prepared/` are distributed under CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/ . Retain this attribution and describe further changes when sharing those files. Code written for this adapter is separate from the upstream data.
