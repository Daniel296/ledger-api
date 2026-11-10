#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 3 ] || [ $# -gt 4 ]; then
    echo "Usage: $0 <merchantAccountId> <amount> <currency> [paymentId]" >&2
    echo "Sends a payment-settled message, as the payment system would, to the Kafka of docker compose." >&2
    echo "Every message gets a new event id; pass the paymentId of an earlier one to send that payment again." >&2
    exit 1
fi

kafka() {
    docker compose -f docker/docker-compose.yml exec -T kafka "$@"
}

new_id() {
    kafka cat /proc/sys/kernel/random/uuid
}

payment_id=${4:-$(new_id)}
message=$(printf '{"eventId": "%s", "paymentId": "%s", "merchantAccountId": "%s", "amount": "%s", "currency": "%s"}' \
    "$(new_id)" "$payment_id" "$1" "$2" "$3")

printf '%s\t%s\n' "$payment_id" "$message" | kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic payment-settled \
    --reader-property parse.key=true --reader-property "key.separator=$(printf '\t')"

echo "Sent: $message"
