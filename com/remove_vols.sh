# Define a list of volume names to remove
volume_names=(
  "cega-auth-claim0"
  "cegamq-claim0"
  "cegamq-claim1"
  "cegamq-claim2"
  "db-claim0"
  "doa-claim0"
  "ingest-claim0"
  "ingest-claim1"
  "tsd-claim0"
  "verify-claim0"
  "verify-claim1"
)

# Loop through the list of volume names and remove them
for volume in "${volume_names[@]}"; do
  echo "Removing volume: $volume"
  podman volume rm "$volume" || echo "Failed to remove volume: $volume"
done

echo "Volume removal process completed."
