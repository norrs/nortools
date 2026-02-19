def _krema_toml_info_impl(ctx):
    out = ctx.outputs.out
    info = ctx.info_file
    version = ctx.version_file
    template = ctx.file.template

    ctx.actions.run_shell(
        inputs = [info, version, template],
        outputs = [out],
        arguments = [info.path, version.path, template.path, out.path],
        command = """
set -eu

info_file="$1"
version_file="$2"
template_file="$3"
out="$4"

get_value() {
  key="$1"
  file="$2"
  awk -v k="$key" '$1 == k { $1=""; sub(/^ /, ""); print; exit }' "$file"
}

get_value_any() {
  key="$1"
  value="$(get_value "$key" "$info_file")"
  if [ -z "${value}" ]; then
    value="$(get_value "$key" "$version_file")"
  fi
  echo "$value"
}

normalize_for_krema() {
  raw="$1"
  if [ -z "$raw" ] || [ "$raw" = "unknown" ]; then
    echo "0.0.0"
    return
  fi

  s="$(printf '%s' "$raw" | sed -E 's/^[vV]//; s/\\.\\+/./g; s/\\+/./g; s/\\.{2,}/./g; s/-.*$//; s/^\\.//; s/\\.$//')"

  if [ -z "$s" ]; then
    s="0.0.0"
  fi
  echo "$s"
}

raw_version="$(get_value_any STABLE_GIT_DESCRIBE)"
krema_version="$(get_value_any STABLE_KREMA_VERSION)"
if [ -z "$krema_version" ] || [ "$krema_version" = "unknown" ]; then
  krema_version="$(normalize_for_krema "$raw_version")"
fi

awk -v v="$krema_version" '
  /^[[:space:]]*version[[:space:]]*=/ { print "version = \\"" v "\\""; next }
  { print }
' "$template_file" > "$out"
""",
    )

    return [DefaultInfo(files = depset([out]))]

krema_toml_info = rule(
    implementation = _krema_toml_info_impl,
    attrs = {
        "template": attr.label(mandatory = True, allow_single_file = True),
    },
    outputs = {"out": "krema.generated.toml"},
)
