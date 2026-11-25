"""The model boundary for this service: E2A SimulatedLLMService (no model access).

One rule answers the audit agent's prompt deterministically, citing only the
policy ids the prompt contains. Swapping in a real provider adapter is a
binding change in main.py; the agent does not change.
"""
import os
import re

from e2a_simulated_llm import SimulatedLLMService, system_contains

AGENT_MARKER = "settlement audit agent"


def _explain(messages, ctx) -> str:
    user = str(messages[-1].get("content", ""))
    settlement = re.search(r"Settlement: (.+)", user)
    rules = re.findall(r"^- RULE (\S+) \((POL-[A-Z]+-\d+)\): (.+)$", user, re.M)
    policies = dict(re.findall(r"^\[(POL-[A-Z]+-\d+)\] (.+)$", user, re.M))
    parts = [f"Settlement {settlement.group(1) if settlement else '?'} was flagged."]
    for name, policy_id, detail in rules:
        clause = policies.get(policy_id)
        if clause:
            parts.append(f"Rule {name}: {detail}. Under {policy_id}: {clause}")
    return " ".join(parts)


def build_audit_llm(profile: str = None):
    """The simulated model, or (GCP-01) the model of the E2A provider profile the deployment names
    in E2A_PROVIDER_PROFILE, e.g. gemini_api (the `gemini` extra). The agent and its checks are the same."""
    profile = (profile if profile is not None else os.getenv("E2A_PROVIDER_PROFILE", "")).strip()
    if profile and profile != "simulated":
        from e2a_managed import bindings_from_profile
        return bindings_from_profile(profile).llm_service
    return SimulatedLLMService().add_rule("audit:explain", system_contains(AGENT_MARKER), _explain)
