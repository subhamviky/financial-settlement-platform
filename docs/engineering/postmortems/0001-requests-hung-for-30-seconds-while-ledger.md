# 0001: Requests hung for 30 seconds while the ledger database was down

- **Date of the work:** 2026-11-27
- **Severity:** SEV-3 (see the platform README)
- **Status:** Actions partly open
- **Authors:** repository owner, with Claude

## Summary

Drill 1 stopped the ledger VM. Settlement-service did not fail fast: requests waited for the connection pool's 30-second timeout, so the health probe saw no answer (`000`) instead of a quick 503.

## Impact

None on users. A real caller would have waited 30 s per request during an outage, holding Cloud Run capacity.

## Timeline

- Drill 1: VM stopped; 45 s later health returns no answer within the probe's 10 s limit.
- The log shows `Connect timed out` and `HikariPool-1 - Connection is not available, request timed out after 30000ms`.
- VM started; health 200 again 40 s after the start request; no manual repair.
- Change: the Google Cloud profile sets `connection-timeout` to 5000 ms, with a test that fails if it is changed.

## Root cause

1. The default pool timeout is tuned for a database that is usually up, not for a caller that wants a typed failure.
2. Nobody had stopped the database before, so the behaviour was not observed.

## What went well

The system did not invent a result or half-write anything; recovery needed no repair.

## Action items

| Action | Type | Status |
|---|---|---|
| 5 s connection timeout on the GCP profile | mitigate | Done |
| Repeat drill 1 to measure the new behaviour | detect | Open |
