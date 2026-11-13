"""The findings index is paged, so it never outgrows one document (Cosmos DB's limit would have
stopped the feed at about 45,000 audited events per tenant)."""
import threading

from e2a_inmemory_adapters import InMemoryTransactionalStore

from audit_ai_service.services.paged_index import PagedIndex
from audit_ai_service.services.state import state_ports


def test_pages_roll_over_and_keep_the_order():
    rows = InMemoryTransactionalStore()
    index = PagedIndex(rows, "findings:acme", page_size=10)
    for i in range(25):
        index.append(f"e{i}")
    assert index.ids() == [f"e{i}" for i in range(25)]
    assert rows.get("findings:acme:head") == {"pages": 3}
    assert all(len(rows.get(f"findings:acme:page:{n}")["ids"]) <= 10 for n in range(3))


def test_an_id_is_listed_once():
    index = PagedIndex(InMemoryTransactionalStore(), "x", page_size=3)
    for item in ("a", "b", "a", "c", "c"):
        index.append(item)
    assert index.ids() == ["a", "b", "c"]


def test_concurrent_appends_lose_nothing():
    """On the configured store: in memory, or on Cosmos DB when the suite runs there."""
    rows, _ = state_ports()
    index = PagedIndex(rows, "findings:race", page_size=7)

    def append(worker):
        for i in range(15):
            index.append(f"w{worker}-{i}")
    threads = [threading.Thread(target=append, args=(w,)) for w in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(index.ids()) == sorted(f"w{w}-{i}" for w in range(4) for i in range(15))
