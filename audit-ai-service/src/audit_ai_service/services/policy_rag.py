"""Policy retrieval on the E2A BaseRAGPipeline contract.

retrieve() is inherited (the single public entry point); this class supplies the
index search, the re-ranking and the groundedness evaluation. The index is a
keyword index over the policy paragraphs; every chunk keeps its policy id and
source file (lineage), so an answer can be checked against what was retrieved.
"""
import re
from pathlib import Path
from typing import Dict, List

from e2a_base import BaseRAGPipeline

POLICY_ID = re.compile(r"\bPOL-[A-Z]+-\d+\b")
WORD = re.compile(r"[a-z]+")


class PolicyRAGPipeline(BaseRAGPipeline):
    def __init__(self, policy_dir: Path) -> None:
        self.chunks: List[Dict[str, object]] = []
        for path in sorted(Path(policy_dir).glob("*.md")):
            for paragraph in path.read_text(encoding="utf-8").split("\n\n"):
                match = POLICY_ID.search(paragraph)
                if match:
                    self.chunks.append({"policy_id": match.group(0), "text": paragraph.strip(),
                                        "source": path.name, "words": set(WORD.findall(paragraph.lower()))})

    def _search_index(self, query_text, query_vector, config=None, tenant_id=None, **kwargs):
        wanted_ids = set(POLICY_ID.findall(query_text))
        words = set(WORD.findall(query_text.lower()))
        results = []
        for chunk in self.chunks:
            score = len(words & chunk["words"]) + (10 if chunk["policy_id"] in wanted_ids else 0)
            if score:
                results.append({"policy_id": chunk["policy_id"], "text": chunk["text"],
                                "source": chunk["source"], "score": score})
        return results

    def _rerank(self, results, config=None, **kwargs):
        top_n = int(kwargs.get("rerank_top_n", 3))
        return sorted(results, key=lambda r: (-r["score"], r["policy_id"]))[:top_n]

    def _evaluate_answer(self, answer, config=None, **kwargs) -> float:
        """Groundedness: share of policy ids cited in the answer that were retrieved."""
        retrieved = set(kwargs.get("retrieved_ids", []))
        cited = set(POLICY_ID.findall(answer or ""))
        if not cited:
            return 0.0
        return len(cited & retrieved) / len(cited)

    def groundedness(self, answer: str, retrieved_ids: List[str]) -> float:
        return self._evaluate_answer(answer, retrieved_ids=retrieved_ids)
