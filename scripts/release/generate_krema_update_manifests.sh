#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-}"
OUTPUT_DIR="${2:-release}"

if [[ -z "$TAG" ]]; then
  echo "Usage: $0 <tag> [output_dir]" >&2
  exit 1
fi

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY is required (owner/repo)" >&2
  exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "GITHUB_TOKEN is required" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

API_BASE="https://api.github.com/repos/${GITHUB_REPOSITORY}"
AUTH_HEADER="Authorization: Bearer ${GITHUB_TOKEN}"

mkdir -p "$OUTPUT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

release_json="$TMP_DIR/release.json"
curl -fsSL \
  -H "$AUTH_HEADER" \
  -H "Accept: application/vnd.github+json" \
  "$API_BASE/releases/tags/$TAG" \
  > "$release_json"

published_at="$(jq -r '.published_at // .created_at // ""' "$release_json")"
release_notes_file="$TMP_DIR/release-notes.txt"
jq -r '.body // ""' "$release_json" > "$release_notes_file"
version="${TAG#v}"

if [[ -z "$version" || "$version" == "$TAG" ]]; then
  echo "Tag must follow v<semver> format (example: v0.1.0). Got: $TAG" >&2
  exit 1
fi

# Optional signing support.
# Private key must be base64 PKCS#8 Ed25519 key expected by Krema UpdateSigner.
signer_jar=""
if [[ -n "${KREMA_UPDATER_PRIVATE_KEY_B64:-}" ]]; then
  cat > "$TMP_DIR/SignFile.java" <<'JAVASRC'
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class SignFile {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: SignFile <file> <privateKeyBase64>");
    }
    Path file = Path.of(args[0]);
    String privateKeyBase64 = args[1];

    byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64);
    PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

    Signature sig = Signature.getInstance("Ed25519");
    sig.initSign(privateKey);
    sig.update(Files.readAllBytes(file));

    System.out.print(Base64.getEncoder().encodeToString(sig.sign()));
  }
}
JAVASRC

  javac "$TMP_DIR/SignFile.java"
  signer_jar="$TMP_DIR"
fi

# Target -> release artifact name
TARGETS=(
  "linux-x86_64:nortools-linux-amd64-${TAG}.tar.gz"
  "darwin-x86_64:nortools-macos-x64-${TAG}.tar.gz"
  "darwin-aarch64:nortools-macos-arm64-${TAG}.tar.gz"
  "windows-x86_64:nortools-windows-x64-${TAG}.zip"
)

for entry in "${TARGETS[@]}"; do
  target="${entry%%:*}"
  asset_name="${entry#*:}"

  asset_json="$(jq -cr --arg name "$asset_name" '.assets[] | select(.name == $name)' "$release_json")"
  if [[ -z "$asset_json" ]]; then
    echo "Required release asset is missing: $asset_name" >&2
    exit 1
  fi

  asset_id="$(jq -r '.id' <<<"$asset_json")"
  asset_url="$(jq -r '.browser_download_url' <<<"$asset_json")"
  asset_size="$(jq -r '.size' <<<"$asset_json")"

  local_asset="$TMP_DIR/$asset_name"
  curl -fsSL \
    -H "$AUTH_HEADER" \
    -H "Accept: application/octet-stream" \
    "$API_BASE/releases/assets/$asset_id" \
    -o "$local_asset"

  signature_json='null'
  if [[ -n "$signer_jar" ]]; then
    signature="$(java -cp "$signer_jar" SignFile "$local_asset" "$KREMA_UPDATER_PRIVATE_KEY_B64")"
    signature_json="$(jq -Rn --arg v "$signature" '$v')"
  fi

  manifest_path="$OUTPUT_DIR/nortools-update-${target}.json"
  jq -n \
    --arg version "$version" \
    --rawfile notes "$release_notes_file" \
    --arg pub_date "$published_at" \
    --arg target "$target" \
    --arg url "$asset_url" \
    --argjson size "$asset_size" \
    --argjson signature "$signature_json" \
    '{
      version: $version,
      notes: $notes,
      pub_date: $pub_date,
      platforms: {
        ($target): {
          signature: $signature,
          url: $url,
          size: $size
        }
      }
    }' > "$manifest_path"

done

if [[ -n "${KREMA_UPDATER_PUBLIC_KEY_B64:-}" ]]; then
  printf '%s\n' "${KREMA_UPDATER_PUBLIC_KEY_B64}" > "$OUTPUT_DIR/nortools-updater-public-key.txt"
fi

echo "Generated updater manifests in: $OUTPUT_DIR"
ls -1 "$OUTPUT_DIR" | sed 's/^/ - /'
