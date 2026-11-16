#!/usr/bin/env bash
# Starts the Azure Service Bus emulator (it keeps its state in SQL Edge) with the queues of
# .github/servicebus-emulator.json: settlement-events (the real one) and one test queue per service.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
password="Settle-Emulator-Pass1!"        # a throwaway password for the CI-only database
docker run -d --name sqledge --network host -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD="$password" \
  mcr.microsoft.com/azure-sql-edge:latest
sleep 20
docker run -d --name servicebus --network host -e ACCEPT_EULA=Y -e SQL_SERVER=localhost \
  -e MSSQL_SA_PASSWORD="$password" -e SQL_WAIT_INTERVAL=5 \
  -v "$here/servicebus-emulator.json:/ServiceBus_Emulator/ConfigFiles/Config.json" \
  mcr.microsoft.com/azure-messaging/servicebus-emulator:latest
for _ in $(seq 90); do docker logs servicebus 2>&1 | grep -q "Successfully Up" && break; sleep 2; done
docker logs servicebus 2>&1 | tail -2
