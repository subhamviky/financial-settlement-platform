"""Where this service keeps its state: E2A's ports, chosen by AUDIT_STATE_BACKEND.

  memory   (default) E2A's in-memory reference adapters: tests, local runs, one replica
  cosmos   e2a-adapters' Cosmos DB adapters (design note D-03): durable and shared by every
           Container App replica, so an event is audited once and every replica answers the same

Cosmos DB settings: COSMOS_ENDPOINT, COSMOS_DATABASE (default "audit"), COSMOS_CONTAINER
(default "state"). The service authenticates with its managed identity (DefaultAzureCredential);
COSMOS_KEY is used instead when set, which is how the tests reach the emulator.
"""
import os
from typing import Mapping, Tuple

from e2a import BaseIdempotencyStore, BaseTransactionalStore
from e2a_inmemory_adapters import InMemoryIdempotencyStore, InMemoryTransactionalStore


def cosmos_container(env: Mapping[str, str]):
    from azure.cosmos import CosmosClient
    credential = env.get("COSMOS_KEY")
    if not credential:
        from azure.identity import DefaultAzureCredential
        credential = DefaultAzureCredential()
    client = CosmosClient(env["COSMOS_ENDPOINT"], credential)
    return client.get_database_client(env.get("COSMOS_DATABASE", "audit")).get_container_client(
        env.get("COSMOS_CONTAINER", "state"))


def state_ports(env: Mapping[str, str] = None) -> Tuple[BaseTransactionalStore, BaseIdempotencyStore]:
    """(rows, claims): findings and the feed position, and the claim on every event."""
    env = os.environ if env is None else env
    backend = env.get("AUDIT_STATE_BACKEND", "memory")
    if backend == "memory":
        return InMemoryTransactionalStore(), InMemoryIdempotencyStore()
    if backend == "cosmos":
        from e2a_adapters.azure import CosmosIdempotencyStore, CosmosTransactionalStore
        container = cosmos_container(env)
        return CosmosTransactionalStore(container), CosmosIdempotencyStore(container)
    raise ValueError(f"AUDIT_STATE_BACKEND={backend!r}: use 'memory' or 'cosmos'")
